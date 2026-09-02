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

    /**
     * 应用自己的 payload 大小上限（524,288 字节 = 512 KiB）。
     * 写入 Outbox 前就挡住明显超大的消息；Relay 的隔离机制仍然保留，
     * 负责兼住漏网与后续配置变化的情况。
     */
    static final int MAX_PAYLOAD_BYTES = 512 * 1024;

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入前先检查序列化后的 payload 大小，超过 512 KiB 直接报错，
     * 避免把明显超大的消息写进 Outbox 再让 Relay 反复隔离。
     */
    public void write(String topic, String eventType, String dedupKey, Map<String, Object> payload) {
        OutboxEvent event = new OutboxEvent();
        event.setTopic(topic);
        event.setEventType(eventType);
        event.setDedupKey(dedupKey);
        event.setCreatedAt(Instant.now());
        try {
            Map<String, Object> body = new LinkedHashMap<>(payload);
            body.putIfAbsent("dedupKey", dedupKey);
            String json = objectMapper.writeValueAsString(body);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                throw new IllegalStateException("Outbox 消息超过 512 KiB 上限（" + dedupKey + "）");
            }
            event.setPayload(json);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化 Outbox 消息", e);
        }
        outbox.save(event);
    }
}
