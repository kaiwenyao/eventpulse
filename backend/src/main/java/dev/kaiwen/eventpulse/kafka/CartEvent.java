package dev.kaiwen.eventpulse.kafka;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 消费者内部使用的小 DTO：解析 cart-events / wallet-events 的 JSON 消息。
 * 不是数据库实体；ignoreUnknown 保证旧消费者能读新字段、新消费者能读旧消息。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CartEvent(
        String messageId,
        String eventType,
        Integer schemaVersion,
        String occurredAt,
        Long userId,
        Long itemId,
        Long eventId,
        Integer quantity,
        Integer deltaQuantity,
        Long version,
        Long checkoutId,
        Integer itemCount,
        Integer totalQuantity,
        Long totalAmountCents,
        String dedupKey) {

    public boolean valid() {
        return dedupKey != null && !dedupKey.isBlank()
                && eventType != null && !eventType.isBlank()
                && userId != null;
    }
}
