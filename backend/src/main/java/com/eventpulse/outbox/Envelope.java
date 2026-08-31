package com.eventpulse.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event envelope. Four field groups per the event catalog:
 * identity (eventId/eventType/schemaVersion), aggregate
 * (aggregateType/aggregateId/aggregateSequence), trace
 * (correlationId/causationId/traceId) and occurredAt/producer/payload.
 */
public record Envelope(UUID eventId, String eventType, int schemaVersion,
                       String aggregateType, UUID aggregateId, long aggregateSequence,
                       UUID correlationId, UUID causationId, String traceId,
                       Instant occurredAt, String producer, Map<String, Object> payload) {

    public static final String PRODUCER = "eventpulse-backend";
}
