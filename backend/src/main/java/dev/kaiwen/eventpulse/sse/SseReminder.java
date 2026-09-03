package dev.kaiwen.eventpulse.sse;

/**
 * SSE 轻量提醒：只告诉浏览器「有变化，请刷新」，不携带完整业务对象。
 * 最终结果保存在 PostgreSQL；前端收到提醒后重新调用 REST 接口。
 *
 * 两种提醒共用一条结构：
 * - 订单级：bookingId 非空，推给订阅该订单的连接（现有行为，保持兼容）；
 * - 用户级：bookingId 为空、userId 非空，推给该用户的刷新频道
 *   （购物车 / 钱包 / 订单列表页面）。消息只会送达属于该用户的连接。
 *
 * {@code eventId} 用于去重：同一条提醒收到两次，前端（与服务端订阅者）
 * 最多多刷新一次，不会重复改变业务数据。
 */
public record SseReminder(String eventId, String type, Long bookingId, Long userId, String occurredAt) {

    /** Redis 广播频道：Worker 发布，所有 API 实例订阅。 */
    public static final String REDIS_CHANNEL = "eventpulse:sse";

    public SseReminder(String eventId, String type, Long bookingId, String occurredAt) {
        this(eventId, type, bookingId, null, occurredAt);
    }
}
