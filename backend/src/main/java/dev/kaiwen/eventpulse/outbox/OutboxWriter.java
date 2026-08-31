package dev.kaiwen.eventpulse.outbox;

import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.web.TraceIdFilter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Appends a domain event to the transactional outbox with a gapless
 * per-aggregate sequence. The aggregate counter row is locked and incremented
 * inside the caller's transaction: a rollback rolls the counter back too, so
 * sequences never develop permanent gaps. Never use a PostgreSQL sequence here.
 */
@Component
public class OutboxWriter {

    public static final String TOPIC_CATALOGUE = "catalogue.events.v1";
    public static final String TOPIC_BOOKING = "booking.events.v1";
    public static final String TOPIC_INTERACTION = "interaction.events.v1";
    public static final String TOPIC_NOTIFICATION_COMMANDS = "notification.commands.v1";

    private final JdbcTemplate jdbc;

    public OutboxWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID append(String aggregateType, UUID aggregateId, String topic, String eventType,
            Map<String, Object> payload) {
        jdbc.update("""
                INSERT INTO aggregate_counters (aggregate_type, aggregate_id, next_sequence)
                VALUES (?, ?, 1)
                ON CONFLICT (aggregate_type, aggregate_id) DO NOTHING
                """, aggregateType, aggregateId);
        Long sequence = jdbc.queryForObject("""
                SELECT next_sequence FROM aggregate_counters
                WHERE aggregate_type = ? AND aggregate_id = ? FOR UPDATE
                """, Long.class, aggregateType, aggregateId);
        jdbc.update("UPDATE aggregate_counters SET next_sequence = next_sequence + 1 "
                + "WHERE aggregate_type = ? AND aggregate_id = ?", aggregateType, aggregateId);
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox (event_id, aggregate_type, aggregate_id, sequence, topic, event_type,
                                    correlation_id, trace_id, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                eventId, aggregateType, aggregateId, sequence, topic, eventType, aggregateId,
                normalizeTraceId(), OutboxJson.write(payload));
        return eventId;
    }

    private String normalizeTraceId() {
        String traceId = TraceIdFilter.currentTraceId();
        if (traceId == null || traceId.length() > 64) {
            return null;
        }
        // trace_id is stored as varchar; uuid cast used for correlation only.
        return traceId;
    }
}
