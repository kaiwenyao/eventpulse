package dev.kaiwen.eventpulse.sse;

/**
 * SSE 轻量提醒：只告诉浏览器「有变化，请刷新」，不携带完整业务对象。
 * 最终结果保存在 PostgreSQL；前端收到提醒后重新调用 REST 接口。
 *
 * {@code eventId} 用于去重：同一条提醒收到两次，前端（与服务端订阅者）
 * 最多多刷新一次，不会重复改变业务数据。
 */
public record SseReminder(String eventId, String type, Long bookingId, String occurredAt) {

    /** Redis 广播频道：Worker 发布，所有 API 实例订阅。 */
    public static final String REDIS_CHANNEL = "eventpulse:sse";
}
