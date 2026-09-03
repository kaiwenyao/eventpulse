package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import dev.kaiwen.eventpulse.kafka.CartConsumer;
import dev.kaiwen.eventpulse.kafka.WalletConsumer;
import dev.kaiwen.eventpulse.outbox.KafkaTopics;
import dev.kaiwen.eventpulse.repository.ConsumedEventRepository;
import dev.kaiwen.eventpulse.repository.OutboxRepository;

/**
 * cart-events / wallet-events 的端到端链路（真实 broker + 真实数据库，worker Profile）：
 * - Outbox Relay 把业务事务里写入的钱包 / 购物车事件发到 Kafka；
 * - 独立 consumer group 消费：RECHARGE 生成站内通知，购物车事件累加每日统计；
 * - 同一消息重复投递（dedupKey 相同）不重复产生副作用；
 * - 旧版本（version 更小）的购物车事件即使首次投递也被丢弃；
 * - 消费者数据库事务里「去重记录 + 副作用」同进同退：副作用失败时去重记录一并回滚，
 *   重放可以重新处理（对应「提交后、offset 提交前宕机」的重放安全性）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.profiles.active=worker")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CartWalletKafkaIT {

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
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    @Autowired
    OutboxRepository outbox;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CartConsumer cartConsumer;
    @Autowired
    WalletConsumer walletConsumer;
    @Autowired
    ConsumedEventRepository consumedEvents;

    private long userId;

    @BeforeEach
    void persistUserAndClear() {
        outbox.deleteAll();
        consumedEvents.deleteAll();
        jdbc.update("DELETE FROM cart_daily_stats");
        jdbc.update("DELETE FROM cart_seen_item_versions");
        jdbc.update("DELETE FROM notifications WHERE type = 'WALLET_RECHARGED'");
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = 'it-cart-wallet-kafka@test.dev'", Integer.class);
        if (existing != null && existing > 0) {
            userId = jdbc.queryForObject(
                    "SELECT id FROM users WHERE email = 'it-cart-wallet-kafka@test.dev'", Long.class);
            return;
        }
        jdbc.update("""
                INSERT INTO users (email, password, name, role, wallet_cents)
                VALUES ('it-cart-wallet-kafka@test.dev', 'x', 'K', 'USER', 0)
                """);
        userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = 'it-cart-wallet-kafka@test.dev'", Long.class);
    }

    @AfterEach
    void cleanup() {
        outbox.deleteAll();
    }

    private void writeOutbox(String topic, String eventType, String messageKey, String payload) {
        jdbc.update("""
                INSERT INTO outbox (topic, event_type, payload, dedup_key, message_key, created_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, topic, eventType, payload, dedupOf(payload), messageKey);
    }

    private static String dedupOf(String payload) {
        int start = payload.indexOf("\"dedupKey\":\"") + "\"dedupKey\":\"".length();
        return payload.substring(start, payload.indexOf('"', start));
    }

    private String walletPayload(String dedupKey, long amount, String bizType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", "mid-" + dedupKey);
        payload.put("eventType", "WALLET_LEDGER_RECORDED");
        payload.put("schemaVersion", 1);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("userId", userId);
        payload.put("ledgerId", System.nanoTime());
        payload.put("seqNo", 1);
        payload.put("bizType", bizType);
        payload.put("amountCents", amount);
        payload.put("balanceBeforeCents", 0);
        payload.put("balanceAfterCents", amount);
        payload.put("dedupKey", dedupKey);
        return json(payload);
    }

    private String cartPayload(String eventType, String dedupKey, Long version, Integer quantity, Integer delta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", "mid-" + dedupKey);
        payload.put("eventType", eventType);
        payload.put("schemaVersion", 1);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("userId", userId);
        payload.put("itemId", 9001L);
        payload.put("eventId", 9002L);
        payload.put("quantity", quantity);
        payload.put("deltaQuantity", delta);
        payload.put("version", version);
        payload.put("dedupKey", dedupKey);
        return json(payload);
    }

    private String checkoutPayload(String dedupKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", "mid-" + dedupKey);
        payload.put("eventType", "CART_CHECKOUT_COMPLETED");
        payload.put("schemaVersion", 1);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("userId", userId);
        payload.put("checkoutId", 5100L);
        payload.put("itemCount", 1);
        payload.put("totalQuantity", 2);
        payload.put("totalAmountCents", 2400);
        payload.put("dedupKey", dedupKey);
        return json(payload);
    }

    private String json(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void waitFor(String description, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }

    @Test
    void walletAndCartEventsFlowThroughKafkaWithIdempotentConsumers() throws Exception {
        writeOutbox(KafkaTopics.WALLET_EVENTS, "WALLET_LEDGER_RECORDED", "wallet:" + userId,
                walletPayload("RECHARGE:kafka-it-1", 50000, "RECHARGE"));
        writeOutbox(KafkaTopics.CART_EVENTS, "CART_ITEM_ADDED", "cart:" + userId,
                cartPayload("CART_ITEM_ADDED", "CART_ITEM:kafka-it-1:v1", 1L, 2, 2));
        writeOutbox(KafkaTopics.CART_EVENTS, "CART_CHECKOUT_COMPLETED", "cart:" + userId,
                checkoutPayload("CART_CHECKOUT:kafka-it-1"));

        // 业务查询以数据库为准；这里的断言只验证异步链路的副作用最终到达。
        waitFor("RECHARGE notification", () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE dedup_key = 'RECHARGE:kafka-it-1'", Long.class) == 1);
        waitFor("cart stats", () -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM cart_daily_stats");
            return !rows.isEmpty()
                    && ((Number) rows.get(0).get("items_added")).longValue() == 1
                    && ((Number) rows.get(0).get("quantity_added")).longValue() == 2
                    && ((Number) rows.get(0).get("checkouts")).longValue() == 1
                    && ((Number) rows.get(0).get("purchased_quantity")).longValue() == 2
                    && ((Number) rows.get(0).get("purchased_amount_cents")).longValue() == 2400;
        });

        // 重复投递同一消息（Outbox 重发 / offset 重放）：副作用不重复。
        writeOutbox(KafkaTopics.WALLET_EVENTS, "WALLET_LEDGER_RECORDED", "wallet:" + userId,
                walletPayload("RECHARGE:kafka-it-1", 50000, "RECHARGE"));
        writeOutbox(KafkaTopics.CART_EVENTS, "CART_ITEM_ADDED", "cart:" + userId,
                cartPayload("CART_ITEM_ADDED", "CART_ITEM:kafka-it-1:v1", 1L, 2, 2));
        Thread.sleep(3000);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE dedup_key = 'RECHARGE:kafka-it-1'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT items_added FROM cart_daily_stats", Long.class)).isEqualTo(1);

        // 旧版本事件（version 小于已统计版本）：即使首次投递也被丢弃，不重复计数。
        writeOutbox(KafkaTopics.CART_EVENTS, "CART_ITEM_ADDED", "cart:" + userId,
                cartPayload("CART_ITEM_ADDED", "CART_ITEM:kafka-it-stale:v0", 0L, 2, 2));
        writeOutbox(KafkaTopics.CART_EVENTS, "CART_ITEM_ADDED", "cart:" + userId,
                cartPayload("CART_ITEM_ADDED", "CART_ITEM:kafka-it-v2:v2", 2L, 5, 5));
        waitFor("version 2 counted", () -> jdbc.queryForObject(
                "SELECT quantity_added FROM cart_daily_stats", Long.class) == 7);
        assertThat(jdbc.queryForObject(
                "SELECT items_added FROM cart_daily_stats", Long.class)).isEqualTo(2);
        // outbox 已全部发出
        waitFor("outbox drained", () -> outbox.countByPublishedAtIsNullAndFailedAtIsNull() == 0);
    }

    @Test
    void consumerDedupAndSideEffectsCommitOrRollBackTogether() {
        // 副作用与去重记录同一数据库事务：直接调用消费者，同一条消息处理两次
        // 只产生一份副作用（通知唯一、统计不翻倍）。
        String json = walletPayload("RECHARGE:direct-1", 30000, "RECHARGE");
        walletConsumer.onMessage(json);
        long firstCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE dedup_key = 'RECHARGE:direct-1'", Long.class);
        walletConsumer.onMessage(json);
        assertThat(firstCount).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE dedup_key = 'RECHARGE:direct-1'", Long.class))
                .isEqualTo(1);

        // 副作用失败（通知 dedup_key 唯一约束）→ 整个消费者事务回滚，
        // consumed_events 里的去重记录也被回滚，之后重放可以重新处理。
        jdbc.update("""
                INSERT INTO notifications (user_id, type, title, message, dedup_key, created_at)
                VALUES (?, 'WALLET_RECHARGED', 'pre', 'pre-existing', 'RECHARGE:direct-2', now())
                """, userId);
        assertThatThrownBy(() -> walletConsumer.onMessage(walletPayload("RECHARGE:direct-2", 100, "RECHARGE")))
                .isInstanceOf(RuntimeException.class);
        // 去重记录被回滚：重放不会被误判为「已处理」
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM consumed_events WHERE consumer_group = ? AND dedup_key = 'RECHARGE:direct-2'",
                Long.class, WalletConsumer.CONSUMER_GROUP)).isZero();
        jdbc.update("DELETE FROM notifications WHERE dedup_key = 'RECHARGE:direct-2'");
        // 重放成功
        walletConsumer.onMessage(walletPayload("RECHARGE:direct-2", 100, "RECHARGE"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM consumed_events WHERE consumer_group = ? AND dedup_key = 'RECHARGE:direct-2'",
                Long.class, WalletConsumer.CONSUMER_GROUP)).isEqualTo(1);

        // 购物车消费者的字段校验：缺少必要字段的消息异常抛出（交 Error Handler 进 DLT）。
        assertThatThrownBy(() -> cartConsumer.onMessage(
                "{\"dedupKey\":\"CART_ITEM:bad:v1\",\"itemId\":9001}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
