package dev.kaiwen.eventpulse.outbox;

import java.util.concurrent.TimeUnit;

/**
 * 事件 Topic 常量：Relay 与 Consumer 共用，避免魔法字符串分散。
 */
public final class KafkaTopics {

    public static final String BOOKING_EVENTS = "booking-events";
    public static final String BOOKING_EVENTS_DLT = "booking-events.DLT";

    private KafkaTopics() {
    }
}