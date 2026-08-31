package dev.kaiwen.eventpulse.kafka;

import java.util.Map;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.entity.Booking;

/**
 * 预订写库成功后，往 Kafka 发一条 JSON。这是本项目要学的核心：先落库，再发消息。
 */
@Component
public class BookingProducer {

    public static final String TOPIC = "booking-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public BookingProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void sendCreated(Booking booking) {
        send("BOOKING_CREATED", booking);
    }

    public void sendCancelled(Booking booking) {
        send("BOOKING_CANCELLED", booking);
    }

    private void send(String type, Booking booking) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", type,
                    "bookingId", booking.getId(),
                    "eventId", booking.getEventId(),
                    "userId", booking.getUserId(),
                    "quantity", booking.getQuantity()));
            kafkaTemplate.send(TOPIC, booking.getId().toString(), json);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化 Kafka 消息", e);
        }
    }
}
