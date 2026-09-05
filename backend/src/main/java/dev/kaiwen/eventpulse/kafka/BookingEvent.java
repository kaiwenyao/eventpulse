package dev.kaiwen.eventpulse.kafka;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Consumer 内部使用的小 DTO：解析 booking-events 的 JSON 消息。
 * 不是数据库实体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingEvent(
        String type,
        String dedupKey,
        Long userId,
        Long eventId,
        Long bookingId,
        String title,
        String message,
        Long quantity) {

    /**
     * 预订消息必须包含这些字段，缺少任何一个都不能写入不完整的 interaction，
     * 应让异常继续抛出并最终进入 DLT。
     */
    public boolean validForInteraction() {
        return dedupKey != null && !dedupKey.isBlank()
                && userId != null
                && eventId != null
                && bookingId != null;
    }
}
