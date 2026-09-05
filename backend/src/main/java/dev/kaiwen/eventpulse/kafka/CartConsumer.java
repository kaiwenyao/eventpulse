package dev.kaiwen.eventpulse.kafka;

import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.outbox.KafkaTopics;
import dev.kaiwen.eventpulse.repository.ConsumedEventRepository;
import dev.kaiwen.eventpulse.sse.SseReminderPublisher;

/**
 * 消费 cart-events（独立 consumer group「eventpulse-cart」），在一个数据库事务里：
 *  1. consumed_events 去重（重复投递直接结束）；
 *  2. 按版本丢弃乱序旧事件（cart_seen_item_versions 记录每个购物车项已统计的版本）；
 *  3. 累加 cart_daily_stats（异步统计，有延迟，不用于余额 / 库存判断）；
 *  4. 注册事务提交后的用户级 SSE 提醒（CART_CHANGED → 其他页面刷新购物车）。
 *
 * 统计口径：
 * - CART_ITEM_ADDED：加购次数 +1、加购票数 += deltaQuantity（仅「新加入购物车」；
 *   合并进已有行是 UPDATED，不计入，避免重复计数）；
 * - CART_ITEM_UPDATED：不计入统计（只推进版本号）；
 * - CART_ITEM_REMOVED：移除行数 +1、移除票数 += quantity；
 * - CART_CHECKOUT_COMPLETED：结算次数 +1、成交票数与金额按汇总事件累计一次
 *   （单订单成交由 booking-events 统计，这里不重复算）。
 */
@Component
@Profile("worker")
public class CartConsumer {

    public static final String CONSUMER_GROUP = "eventpulse-cart";

    private final ConsumedEventRepository consumedEvents;
    private final SseReminderPublisher reminders;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CartConsumer(
            ConsumedEventRepository consumedEvents,
            SseReminderPublisher reminders,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.consumedEvents = consumedEvents;
        this.reminders = reminders;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.CART_EVENTS, groupId = CONSUMER_GROUP)
    @Transactional
    public void onMessage(String json) {
        CartEvent event = parse(json);
        if (!event.valid()) {
            throw new IllegalStateException("Cart message missing required fields: " + json);
        }

        boolean firstTime = consumedEvents.tryInsert(CONSUMER_GROUP, event.dedupKey()) == 1;
        if (!firstTime) {
            return;
        }

        if ("CART_CHECKOUT_COMPLETED".equals(event.eventType())) {
            jdbc.update("""
                    INSERT INTO cart_daily_stats
                        (stat_date, items_added, quantity_added, items_removed, quantity_removed,
                         checkouts, purchased_quantity, purchased_amount_cents)
                    VALUES (CURRENT_DATE, 0, 0, 0, 0, 1, ?, ?)
                    ON CONFLICT (stat_date) DO UPDATE
                      SET checkouts = cart_daily_stats.checkouts + 1,
                          purchased_quantity = cart_daily_stats.purchased_quantity + ?,
                          purchased_amount_cents = cart_daily_stats.purchased_amount_cents + ?
                    """,
                    // 装箱类型的空安全兜底：三元两侧都用装箱字面量（Integer/Long.valueOf），
                    // 避免 int/Integer 混合三元触发的「拆箱后立刻再装箱」（BX_UNBOXING_IMMEDIATELY_REBOXED）。
                    event.totalQuantity() == null ? Integer.valueOf(0) : event.totalQuantity(),
                    event.totalAmountCents() == null ? Long.valueOf(0L) : event.totalAmountCents(),
                    event.totalQuantity() == null ? Integer.valueOf(0) : event.totalQuantity(),
                    event.totalAmountCents() == null ? Long.valueOf(0L) : event.totalAmountCents());
        }
        else if (event.itemId() != null && isItemEvent(event.eventType())) {
            if (staleVersion(event.itemId(), event.version())) {
                // 旧版本事件（乱序 / 延迟到达）：去重记录已写，副作用必须跳过，
                // 避免旧消息覆盖新状态或重复计数。
                reminders.remindUser(event.userId(), "CART_CHANGED", "sse:" + event.dedupKey());
                return;
            }
            switch (event.eventType()) {
                case "CART_ITEM_ADDED" -> jdbc.update("""
                        INSERT INTO cart_daily_stats (stat_date, items_added, quantity_added)
                        VALUES (CURRENT_DATE, 1, ?)
                        ON CONFLICT (stat_date) DO UPDATE
                          SET items_added = cart_daily_stats.items_added + 1,
                              quantity_added = cart_daily_stats.quantity_added + ?
                        """,
                        event.deltaQuantity() == null ? Integer.valueOf(0) : event.deltaQuantity(),
                        event.deltaQuantity() == null ? Integer.valueOf(0) : event.deltaQuantity());
                case "CART_ITEM_REMOVED" -> jdbc.update("""
                        INSERT INTO cart_daily_stats (stat_date, items_removed, quantity_removed)
                        VALUES (CURRENT_DATE, 1, ?)
                        ON CONFLICT (stat_date) DO UPDATE
                          SET items_removed = cart_daily_stats.items_removed + 1,
                              quantity_removed = cart_daily_stats.quantity_removed + ?
                        """,
                        event.quantity() == null ? Integer.valueOf(0) : event.quantity(),
                        event.quantity() == null ? Integer.valueOf(0) : event.quantity());
                default -> {
                    // CART_ITEM_UPDATED 只推进版本号，不计入统计。
                }
            }
            jdbc.update("""
                    INSERT INTO cart_seen_item_versions (item_id, last_version)
                    VALUES (?, ?)
                    ON CONFLICT (item_id) DO UPDATE
                      SET last_version = GREATEST(cart_seen_item_versions.last_version, ?)
                    """,
                    event.itemId(), event.version() == null ? Long.valueOf(0L) : event.version(),
                    event.version() == null ? Long.valueOf(0L) : event.version());
        }

        // 提交成功后才发布：提醒该用户的其他页面 / 设备刷新购物车。
        reminders.remindUser(event.userId(), "CART_CHANGED", "sse:" + event.dedupKey());
    }

    private static boolean isItemEvent(String eventType) {
        return "CART_ITEM_ADDED".equals(eventType)
                || "CART_ITEM_UPDATED".equals(eventType)
                || "CART_ITEM_REMOVED".equals(eventType);
    }

    private boolean staleVersion(Long itemId, Long version) {
        if (version == null) {
            return false;
        }
        var rows = jdbc.queryForList(
                "SELECT last_version FROM cart_seen_item_versions WHERE item_id = ?", itemId);
        if (rows.isEmpty()) {
            return false;
        }
        return ((Number) rows.get(0).get("last_version")).longValue() >= version;
    }

    private CartEvent parse(String json) {
        try {
            return objectMapper.readValue(json, CartEvent.class);
        }
        catch (Exception e) {
            throw new IllegalStateException("Unable to parse Kafka message: " + json, e);
        }
    }
}
