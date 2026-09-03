package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.Notification;
import dev.kaiwen.eventpulse.entity.OutboxEvent;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.outbox.KafkaTopics;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;
import dev.kaiwen.eventpulse.repository.OutboxRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;

/**
 * Outbox → Kafka → Consumer 的端到端链路（真实 broker + 真实数据库，worker Profile）：
 * - KafkaTopicConfig 在启动时按配置的 partition 数建 topic；
 * - Relay 把同一条订单的两条消息（创建 → 取消）按 message_key 发进同一分区；
 * - Consumer 顺序消费，通知按业务顺序落库，重放（dedup_key 相同）不会重复生成。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.profiles.active=worker")
@Testcontainers(disabledWithoutDocker = true)
// 本上下文自带「每秒一轮的 relay」和「自启动的 Kafka listener」。类测完后上下文会留在
// JVM 级缓存里，relay 继续对着共享的 SharedPostgres 抢领 pending 消息（此时 Kafka 容器
// 已停，发送必然失败再释放），把 WorkerBackgroundTasksIT 这类直接断言 outbox 行状态的
// 测试搅成概率性失败。类结束即关闭上下文，调度器和 listener 一并停下。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaOutboxE2EIT {

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.POSTGRES.getPassword());
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("eventpulse.redis-enabled", () -> "false");
        // application-test.yml 默认关闭 listener 自启动；本 IT 要让 consumer 真正消费。
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    @Autowired
    OutboxRepository outbox;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    UserRepository users;
    @Autowired
    EventRepository events;

    @BeforeEach
    void clearOutbox() {
        outbox.deleteAll();
    }

    @AfterEach
    void clearOutboxAgain() {
        outbox.deleteAll();
    }

    /** interactions / event_daily_metrics 外键指向 users 与 events：先建真实行。 */
    private Long[] persistUserAndEvent() {
        User organiser = new User();
        organiser.setEmail("it-kafka-" + System.nanoTime() + "@test.dev");
        organiser.setPassword("x");
        organiser.setName("IT");
        organiser.setRole("ORGANISER");
        organiser.setWalletCents(0);
        Long userId = users.save(organiser).getId();

        Event event = new Event();
        event.setTitle("Kafka IT");
        event.setDescription("it");
        event.setCategory("music");
        event.setCity("Shanghai");
        Instant startsAt = Instant.now().plusSeconds(86400);
        event.setStartsAt(startsAt);
        event.setEndsAt(startsAt.plusSeconds(3600));
        event.setPriceCents(0);
        event.setCapacity(10);
        event.setSold(0);
        event.setMaxQuantityPerBooking(10);
        event.setOrganiserId(userId);
        event.setStatus("PUBLISHED");
        event.setCreatedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        Long eventId = events.save(event).getId();
        return new Long[] {userId, eventId};
    }

    private void write(String messageKey, String dedupKey, String type, Integer quantity, Long userId, Long eventId) {
        OutboxEvent event = new OutboxEvent();
        event.setTopic(KafkaTopics.BOOKING_EVENTS);
        event.setEventType(type);
        event.setMessageKey(messageKey);
        event.setDedupKey(dedupKey);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("type", type);
        body.put("dedupKey", dedupKey);
        body.put("userId", userId);
        body.put("eventId", eventId);
        body.put("bookingId", 4200L);
        if (quantity != null) {
            body.put("quantity", quantity);
        }
        event.setPayload(objectMapperJson(body));
        event.setCreatedAt(Instant.now());
        outbox.save(event);
    }

    private String objectMapperJson(Map<String, Object> body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void relayPublishesAndConsumerCreatesNotificationsInOrder() throws Exception {
        String messageKey = "booking:4200";
        String dedupCreated = "BOOKING_CREATED:4200";
        String dedupCancelled = "BOOKING_CANCELLED:4200";
        Long[] ids = persistUserAndEvent();

        write(messageKey, dedupCreated, "BOOKING_CREATED", 2, ids[0], ids[1]);
        write(messageKey, dedupCancelled, "BOOKING_CANCELLED", null, ids[0], ids[1]);

        // Relay 每秒一轮；等待两条消息被发布、消费并落库（通知出现）。
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (notifications.existsByDedupKey(dedupCreated)
                    && notifications.existsByDedupKey(dedupCancelled)) {
                break;
            }
            Thread.sleep(300);
        }
        assertThat(notifications.existsByDedupKey(dedupCreated)).as("创建通知已生成").isTrue();
        assertThat(notifications.existsByDedupKey(dedupCancelled)).as("取消通知已生成").isTrue();

        // 同键保序：创建的通知先于取消的通知落库（同分区顺序消费）。
        List<Notification> rows = notifications.findByBookingIdInOrderByCreatedAtDesc(List.of(4200L)).stream()
                .filter(n -> dedupCreated.equals(n.getDedupKey()) || dedupCancelled.equals(n.getDedupKey()))
                .toList();
        assertThat(rows).hasSize(2);
        Notification created = rows.stream().filter(n -> dedupCreated.equals(n.getDedupKey())).findFirst().orElseThrow();
        Notification cancelled = rows.stream().filter(n -> dedupCancelled.equals(n.getDedupKey())).findFirst().orElseThrow();
        assertThat(created.getId()).isLessThan(cancelled.getId());

        // outbox 已标记发布，outbox 积压清空。
        assertThat(outbox.countByPublishedAtIsNullAndFailedAtIsNull()).isZero();
    }

    @Test
    void topicIsCreatedWithConfiguredPartitions() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            TopicDescription description = admin.describeTopics(List.of(KafkaTopics.BOOKING_EVENTS))
                    .allTopicNames()
                    .get(30, java.util.concurrent.TimeUnit.SECONDS)
                    .get(KafkaTopics.BOOKING_EVENTS);
            // KafkaTopicConfig 按 eventpulse.kafka.topic-partitions（默认 3）建 topic。
            assertThat(description.partitions()).hasSize(3);
        }
    }
}
