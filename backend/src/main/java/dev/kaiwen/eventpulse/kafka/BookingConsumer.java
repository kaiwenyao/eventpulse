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
            String type = node.path("type").asText("UNKNOWN");
            notifications.save(new Notification(bookingId, "Kafka 已处理：" + type));
        }
        catch (Exception e) {
            log.warn("忽略一条无法解析的 Kafka 消息: {}", json, e);
        }
    }
}
