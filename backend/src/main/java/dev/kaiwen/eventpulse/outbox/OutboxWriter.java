package dev.kaiwen.eventpulse.outbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.entity.OutboxEvent;
import dev.kaiwen.eventpulse.repository.OutboxRepository;

@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    public void write(String topic, String eventType, String dedupKey, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setTopic(topic);
        event.setEventType(eventType);
        event.setDedupKey(dedupKey);
        event.setCreatedAt(Instant.now());
        try {
            Map<String, Object> body = new LinkedHashMap<>(payload);
            body.putIfAbsent("dedupKey", dedupKey);
            event.setPayload(objectMapper.writeValueAsString(body));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化 Outbox 消息", e);
        }
        outbox.save(event);
    }
}
