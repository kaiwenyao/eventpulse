package dev.kaiwen.eventpulse.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 把提醒推给连接在本实例上的浏览器；并负责心跳与停机清理。
 * 收到 Redis 广播后，只有订单恰好连接在本实例的浏览器会收到 SSE。
 */
@Component
@Profile("api")
public class SseNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SseNotificationService.class);

    private final SseConnectionRegistry registry;

    public SseNotificationService(SseConnectionRegistry registry) {
        this.registry = registry;
    }

    /** Worker 处理完成后的提醒推给本机匹配的连接：订单级按 bookingId，用户级按 userId。 */
    public void broadcast(SseReminder reminder) {
        if (reminder.bookingId() != null) {
            int sent = registry.send(reminder.bookingId(), "reminder", reminder);
            log.debug("SSE 提醒 bookingId={} type={} 送达 {} 条连接", reminder.bookingId(), reminder.type(), sent);
            return;
        }
        if (reminder.userId() != null) {
            int sent = registry.sendToUser(reminder.userId(), "reminder", reminder);
            log.debug("SSE 用户级提醒 userId={} type={} 送达 {} 条连接", reminder.userId(), reminder.type(), sent);
        }
    }

    /** 定期心跳：避免空闲连接被代理与负载均衡器关闭。 */
    @Scheduled(fixedDelayString = "${eventpulse.sse.heartbeat-ms:25000}")
    public void heartbeat() {
        registry.heartbeat();
    }

    /** 优雅停机：主动关闭连接，浏览器立即收到断开事件并重连（先 REST 补偿再订阅）。 */
    @PreDestroy
    public void shutdown() {
        int closed = registry.closeAll();
        if (closed > 0) {
            log.info("API 停机：已主动关闭 {} 条 SSE 连接，浏览器将自动重连", closed);
        }
    }
}
