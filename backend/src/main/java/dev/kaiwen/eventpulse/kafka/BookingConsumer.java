package dev.kaiwen.eventpulse.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.entity.Notification;
import dev.kaiwen.eventpulse.repository.NotificationRepository;

/**
 * 消费 booking-events，写成一条通知。打开「消息」页就能看到 Kafka 生效了。
 */
@Component
public class BookingConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingConsumer.class);

    private final NotificationRepository notifications;
    private final ObjectMapper objectMapper;

    public BookingConsumer(NotificationRepository notifications, ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = BookingProducer.TOPIC, groupId = "eventpulse")
    public void onMessage(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            long bookingId = node.path("bookingId").asLong();
            Long userId = node.path("userId").isNumber() ? node.path("userId").asLong() : null;
            Long eventId = node.path("eventId").isNumber() ? node.path("eventId").asLong() : null;
            String type = node.path("type").asText("UNKNOWN");
            String dedupKey = node.path("dedupKey").asText(type + ":" + bookingId);
            if (dedupKey != null && !dedupKey.isBlank() && notifications.existsByDedupKey(dedupKey)) {
                return;
            }
            Notification notice = new Notification(bookingId == 0 ? null : bookingId,
                    node.path("message").asText("已处理：" + type));
            notice.setUserId(userId);
            notice.setEventId(eventId);
            notice.setType(type);
            notice.setTitle(node.path("title").asText("消息通知"));
            notice.setPayload(json);
            notice.setDedupKey(dedupKey);
            notifications.save(notice);
        }
        catch (Exception e) {
            log.warn("忽略一条无法解析的 Kafka 消息: {}", json, e);
        }
    }
}
