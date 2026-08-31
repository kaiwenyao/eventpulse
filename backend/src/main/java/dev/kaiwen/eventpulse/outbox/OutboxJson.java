package dev.kaiwen.eventpulse.outbox;

import tools.jackson.databind.ObjectMapper;

/**
 * Shared Jackson 3 mapper for outbox payloads and Kafka envelopes.
 * Event payloads carry only whitelisted business fields.
 */
public final class OutboxJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OutboxJson() {
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
