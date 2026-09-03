package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.repository.UserRepository;
import dev.kaiwen.eventpulse.service.WalletService;

/**
 * Kafka 不可用时的行为（worker Profile，bootstrap 指向一个无人监听的端口）：
 * 业务（充值 → 余额 + 流水 + Outbox）照常在数据库事务里提交成功；
 * 事件保留在 Outbox 待发送（领取 / 重试 / 隔离机制继续工作），
 * Kafka 恢复后由 Relay 正常投递——投递路径已由 CartWalletKafkaIT 覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=worker",
        "spring.kafka.bootstrap-servers=localhost:59999",
        // An intentionally unreachable broker can produce tens of thousands of identical
        // rebootstrap INFO messages. Keep warnings/errors without flooding CI/test reports.
        "logging.level.org.apache.kafka.clients.admin.internals.AdminMetadataManager=WARN",
        "eventpulse.outbox.poll-ms=500",
        "eventpulse.outbox.future-wait-seconds=1",
        "eventpulse.outbox.claim-seconds=10",
        "eventpulse.redis-enabled=false"})
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxKafkaDownIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.POSTGRES.getPassword());
    }

    @Autowired
    WalletService wallets;
    @Autowired
    UserRepository users;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void businessCommitsAndOutboxRetainsWhileKafkaIsUnavailable() throws Exception {
        User user = new User();
        user.setEmail("it-kafka-down-" + System.nanoTime() + "@test.dev");
        user.setPassword("x");
        user.setName("K");
        user.setRole("USER");
        user.setWalletCents(0);
        Long userId = users.save(user).getId();

        // 充值走 WalletService.creditOnce：余额、流水、wallet-events Outbox 同一事务提交。
        var mutation = wallets.creditOnce(userId, 12345, "RECHARGE", "RECHARGE:kafka-down-it", "demo");
        assertThat(mutation).isPresent();
        assertThat(mutation.get().balanceAfter()).isEqualTo(12345);

        // 数据库已经一致：余额 + 流水 + 待发送事件都在。
        assertThat(jdbc.queryForObject("SELECT wallet_cents FROM users WHERE id = ?", Long.class, userId))
                .isEqualTo(12345);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE user_id = ? AND external_biz_id = 'RECHARGE:kafka-down-it'",
                Long.class, userId)).isEqualTo(1);

        // 本类与其它 IT 共享同一个 PostgreSQL 容器：先行用例（无 Relay 的 profile）
        // 会留下永远无人投递成功的 pending 行，而 Relay 按 id 升序尝试、临时故障
        // 即整轮暂停，旧行会让本行一直排在队尾，30s 内轮不到发送尝试。
        // 发送等待前清掉历史遗留行，只留本用例自己的行（WorkerBackgroundTasksIT
        // 出于同样的原因在每个测试前后清空 outbox）。
        jdbc.update("""
                DELETE FROM outbox
                 WHERE dedup_key <> 'RECHARGE:kafka-down-it'
                   AND published_at IS NULL AND failed_at IS NULL
                """);

        // Kafka 不可达：等 Relay 真正重试过至少一次；事件绝不会被标记已发布，
        // 仍保留在 Outbox（待发送队列，或在反复失败后被隔离等待人工恢复——
        // 两者都是「消息没有丢、业务也没有被 Kafka 拖垮」的状态）。
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        long attempts;
        do {
            Thread.sleep(500);
            attempts = jdbc.queryForObject(
                    "SELECT publish_attempts FROM outbox WHERE dedup_key = 'RECHARGE:kafka-down-it'",
                    Long.class);
            assertThat(jdbc.queryForObject(
                    "SELECT published_at FROM outbox WHERE dedup_key = 'RECHARGE:kafka-down-it'",
                    java.sql.Timestamp.class)).as("Kafka 不可用时不能标记已发布").isNull();
        }
        while (attempts < 1 && System.nanoTime() < deadline);
        assertThat(attempts).as("Relay 应至少尝试过一次发送").isGreaterThanOrEqualTo(1);
        Map<String, Object> pending = jdbc.queryForMap(
                "SELECT topic, event_type FROM outbox WHERE dedup_key = 'RECHARGE:kafka-down-it'");
        assertThat(pending.get("topic")).isEqualTo("wallet-events");
        assertThat(pending.get("event_type")).isEqualTo("WALLET_LEDGER_RECORDED");
    }
}
