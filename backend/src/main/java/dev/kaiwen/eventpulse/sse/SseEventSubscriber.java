package dev.kaiwen.eventpulse.sse;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 订阅 Redis 的「有新状态」广播（仅 api Profile）。
 * 收到提醒后按 bookingId 找本机连接推送；eventId 用来忽略重复提醒。
 * Redis 短暂中断时订阅会降级，浏览器靠重连后的 REST 查询补上变化。
 */
@Component
@Profile("api")
public class SseEventSubscriber implements MessageListener {

    /** 去重集合上限：足够宽的窗口，防止极端重放把内存撑大。 */
    static final int MAX_REMEMBERED_IDS = 1024;

    private static final Logger log = LoggerFactory.getLogger(SseEventSubscriber.class);

    private final SseNotificationService notifications;
    private final ObjectMapper objectMapper;
    private final Set<String> recentEventIds = ConcurrentHashMap.newKeySet();

    public SseEventSubscriber(SseNotificationService notifications, ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    /** 供测试观察去重窗口大小。 */
    int recentEventIdsSize() {
        return recentEventIds.size();
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            SseReminder reminder = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), SseReminder.class);
            if (reminder.bookingId() == null || reminder.type() == null) {
                log.warn("忽略缺少 bookingId/type 的 SSE 提醒: {}", reminder);
                return;
            }
            String eventId = reminder.eventId() == null || reminder.eventId().isBlank()
                    ? reminder.type() + ":" + reminder.bookingId()
                    : reminder.eventId();
            if (!recentEventIds.add(eventId)) {
                log.debug("忽略重复 SSE 提醒 eventId={}", eventId);
                return;
            }
            if (recentEventIds.size() > MAX_REMEMBERED_IDS) {
                recentEventIds.clear();
            }
            notifications.broadcast(new SseReminder(eventId, reminder.type(), reminder.bookingId(),
                    reminder.occurredAt()));
        }
        catch (Exception e) {
            // 单条坏消息不能影响订阅循环；Redis 里的提醒本来就是可丢弃的。
            log.warn("无法解析 SSE 提醒消息", e);
        }
    }
}
