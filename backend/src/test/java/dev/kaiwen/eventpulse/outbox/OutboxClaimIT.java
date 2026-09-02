package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.kaiwen.eventpulse.entity.OutboxEvent;
import dev.kaiwen.eventpulse.repository.OutboxRepository;

import jakarta.persistence.EntityManagerFactory;

/**
 * 双 Worker 抢占 Outbox 的数据库层测试（真实 PostgreSQL）：
 * - 一条原子 UPDATE 领取：两个 Worker 并发领取不会拿到同一条消息；
 * - 顺序规则：同一 message_key 还有更早未发布消息时，后面的消息暂不领取；
 * - 租约过期后其他 Worker 可以接手；
 * - markPublished 清掉领取信息，消息不会被二次领取。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxClaimIT {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    private OutboxRepository outbox;
    private TransactionTemplate tx;
    private EntityManagerFactory emf;

    @BeforeAll
    void setUp() {
        FlywayMigrator.migrate(postgres);
        DataSource dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(),
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        LocalContainerEntityManagerFactoryBean emfBean = new LocalContainerEntityManagerFactoryBean();
        emfBean.setDataSource(dataSource);
        emfBean.setPackagesToScan("dev.kaiwen.eventpulse.entity");
        emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emfBean.getJpaPropertyMap().put("hibernate.hbm2ddl.auto", "none");
        emfBean.afterPropertiesSet();
        emf = emfBean.getObject();
        tx = new TransactionTemplate(new JpaTransactionManager(emf));
        outbox = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
                .getRepository(OutboxRepository.class);
    }

    @AfterAll
    void tearDown() {
        if (emf != null) {
            emf.close();
        }
    }

    @BeforeEach
    void clearOutbox() {
        // 各测试共用一个数据库：先清掉上一条测试留下的消息，避免串扰。
        tx.executeWithoutResult(status -> outbox.deleteAll());
    }

    private OutboxEvent row(String messageKey) {
        OutboxEvent event = new OutboxEvent();
        event.setTopic("booking-events");
        event.setEventType("BOOKING_CREATED");
        event.setMessageKey(messageKey);
        event.setDedupKey("dedup-" + messageKey);
        event.setPayload("{}");
        return tx.execute(status -> outbox.save(event));
    }

    private List<Long> claim(String token, int batch) {
        return tx.execute(status -> {
            outbox.claimBatch(token, Instant.now().plusSeconds(60), Instant.now(), batch);
            return outbox.findByClaimedByOrderByIdAsc(token).stream()
                    .map(OutboxEvent::getId)
                    .toList();
        });
    }

    @Test
    void twoWorkersClaimDisjointBatches() throws Exception {
        for (int i = 1; i <= 12; i++) {
            row("booking:" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Set<Long> claimedByA = new HashSet<>();
        Set<Long> claimedByB = new HashSet<>();
        try {
            // 两个 Worker 同时领取，直到没有可领取的消息。
            for (int round = 0; round < 5; round++) {
                Future<List<Long>> fa = pool.submit(() -> claim("worker-a", 5));
                Future<List<Long>> fb = pool.submit(() -> claim("worker-b", 5));
                claimedByA.addAll(fa.get(10, TimeUnit.SECONDS));
                claimedByB.addAll(fb.get(10, TimeUnit.SECONDS));
            }
        }
        finally {
            pool.shutdownNow();
        }
        // 交集为空：同一条消息不会被两个 Worker 同时领取。
        assertThat(claimedByA).doesNotContainAnyElementsOf(claimedByB);
        Set<Long> all = new HashSet<>();
        all.addAll(claimedByA);
        all.addAll(claimedByB);
        assertThat(all).hasSize(12);
    }

    @Test
    void sameMessageKeyIsClaimedInInsertOrder() {
        OutboxEvent first = row("booking:100");
        OutboxEvent second = row("booking:100");
        OutboxEvent other = row("booking:101");

        List<Long> claimed = claim("worker-order", 10);

        // 「创建」未发布之前，「取消」不能先领；不同订单并行领取。
        assertThat(claimed).containsExactlyInAnyOrder(first.getId(), other.getId());
        assertThat(claimed).doesNotContain(second.getId());

        // 第一条发布后，同键的第二条才可被领取。
        tx.executeWithoutResult(status -> outbox.markPublished(first.getId(), Instant.now()));
        List<Long> next = claim("worker-order-2", 10);
        assertThat(next).containsExactly(second.getId());
    }

    @Test
    void expiredLeaseCanBeTakenOverByAnotherWorker() {
        OutboxEvent stuck = row("booking:200");
        Instant t0 = Instant.now();
        // Worker 领取后「死亡」：租约还写着未来 60 秒。
        tx.execute(status -> outbox.claimBatch("crashed-worker", t0.plusSeconds(60), t0, 10));

        // 租约有效期内（模拟 t0+30），其他 Worker 领取不到这条消息。
        tx.execute(status -> outbox.claimBatch("healthy-worker-1", t0.plusSeconds(90), t0.plusSeconds(30), 10));
        List<OutboxEvent> takenByWorker1 = tx.execute(status ->
                outbox.findByClaimedByOrderByIdAsc("healthy-worker-1"));
        assertThat(takenByWorker1).isEmpty();

        // 租约到期后（模拟 t0+61），其他 Worker 可以接手。
        tx.execute(status -> outbox.claimBatch("healthy-worker-2", t0.plusSeconds(180), t0.plusSeconds(61), 10));
        List<Long> takeover = tx.execute(status ->
                outbox.findByClaimedByOrderByIdAsc("healthy-worker-2").stream()
                        .map(OutboxEvent::getId)
                        .toList());
        assertThat(takeover).containsExactly(stuck.getId());
    }

    @Test
    void publishedMessagesAreNeverClaimedAgain() {
        OutboxEvent done = row("booking:300");
        tx.executeWithoutResult(status -> outbox.markPublished(done.getId(), Instant.now()));
        assertThat(claim("worker-after-publish", 10)).isEmpty();
        OutboxEvent fetched = tx.execute(status -> outbox.findById(done.getId())).orElseThrow();
        assertThat(fetched.getPublishedAt()).isNotNull();
        assertThat(fetched.getClaimedBy()).isNull();
    }

    @Test
    void quarantinedMessageDoesNotBlockLaterOnesWithSameKey() {
        OutboxEvent bad = row("booking:400");
        tx.executeWithoutResult(status -> outbox.recordFailure(bad.getId(), "too big", Instant.now()));
        OutboxEvent later = row("booking:400");
        List<Long> claimed = claim("worker-quarantine", 10);
        assertThat(claimed).containsExactly(later.getId());
    }

    private static final class FlywayMigrator {
        static void migrate(PostgreSQLContainer<?> postgres) {
            org.flywaydb.core.Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
        }
    }
}
