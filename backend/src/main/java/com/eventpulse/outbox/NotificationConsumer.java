package com.eventpulse.outbox;

import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reference consumer. In one local transaction it: checks/advances the
 * per-aggregate cursor, applies the side effect (notifications), records the
 * event id and writes new outbox rows when needed. Duplicates (sequence <=
 * cursor) are skipped; a gap (sequence > cursor + 1) blocks only that
 * aggregate and is recorded for admin resolution. Offset commits happen after
 * the DB transaction, so a kill between the two is absorbed by the cursor.
 */
@Component
public class NotificationConsumer {

    public static final String CONSUMER_ID = "notification-consumer";

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final JdbcTemplate jdbc;

    public NotificationConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = { OutboxWriter.TOPIC_BOOKING, OutboxWriter.TOPIC_CATALOGUE,
            OutboxWriter.TOPIC_INTERACTION, OutboxWriter.TOPIC_NOTIFICATION_COMMANDS }, concurrency = "1")
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        KafkaConfig.ParsedEnvelope envelope;
        try {
            envelope = KafkaConfig.ParsedEnvelope.parse(record);
        }
        catch (Exception e) {
            // Malformed envelope: let the error handler route it to the DLT.
            throw new IllegalStateException("unparseable envelope on " + record.topic(), e);
        }
        String aggregateType = envelope.aggregateType();
        UUID aggregateId = envelope.aggregateUuid();
        long sequence = envelope.aggregateSequence();

        jdbc.update("""
                INSERT INTO consumer_cursors (consumer, aggregate_type, aggregate_id, last_sequence, last_event_id)
                VALUES (?, ?, ?, 0, NULL)
                ON CONFLICT (consumer, aggregate_type, aggregate_id) DO NOTHING
                """, CONSUMER_ID, aggregateType, aggregateId);

        record Cursor(Long lastSeq, UUID lastEventId) {
        }
        Cursor cursor = jdbc.queryForObject("""
                SELECT last_sequence, last_event_id FROM consumer_cursors
                WHERE consumer = ? AND aggregate_type = ? AND aggregate_id = ? FOR UPDATE
                """, (rs, i) -> new Cursor(rs.getLong("last_sequence"), rs.getObject("last_event_id", UUID.class)),
                CONSUMER_ID, aggregateType, aggregateId);

        if (sequence <= cursor.lastSeq()) {
            // Deliberate redelivery after crash/replay: skip safely.
            acknowledgment.acknowledge();
            return;
        }
        if (sequence > cursor.lastSeq() + 1) {
            recordGap(aggregateType, aggregateId, cursor.lastSeq() + 1, sequence, envelope.eventUuid());
            // Do not advance the cursor: the aggregate stays blocked until an
            // admin resolves the gap. Later events of the same aggregate keep
            // queueing; other aggregates continue unaffected.
            acknowledgment.acknowledge();
            return;
        }

        applySideEffect(aggregateType, envelope);
        jdbc.update("""
                UPDATE consumer_cursors SET last_sequence = ?, last_event_id = ?, updated_at = now()
                WHERE consumer = ? AND aggregate_type = ? AND aggregate_id = ?
                """, sequence, envelope.eventUuid(), CONSUMER_ID, aggregateType, aggregateId);
        acknowledgment.acknowledge();
    }

    private void applySideEffect(String aggregateType, KafkaConfig.ParsedEnvelope envelope) {
        Map<String, Object> payload = envelope.payload() == null ? Map.of() : envelope.payload();
        switch (envelope.eventType()) {
            case "booking.confirmed", "booking.cancelled", "booking.expired", "payment.failed",
                 "refund.succeeded", "refund.failed", "booking.late_capture_compensated" -> {
                Object userId = payload.get("userId");
                if (userId instanceof String uid && !uid.isBlank()) {
                    jdbc.update("""
                            INSERT INTO notifications (user_id, type, payload)
                            VALUES (?::uuid, ?, ?::jsonb)
                            """, uid, envelope.eventType(), OutboxJson.write(payload));
                }
            }
            default -> {
                // Analytics-only event types advance the cursor without a
                // notification side effect.
            }
        }
    }

    private void recordGap(String aggregateType, UUID aggregateId, long expected, long received, UUID eventId) {
        int inserted = jdbc.update("""
                INSERT INTO consumer_gaps (consumer, aggregate_type, aggregate_id, expected, received, event_id)
                SELECT ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                  SELECT 1 FROM consumer_gaps
                  WHERE consumer = ? AND aggregate_type = ? AND aggregate_id = ? AND expected = ?
                )
                """, CONSUMER_ID, aggregateType, aggregateId, expected, received, eventId,
                CONSUMER_ID, aggregateType, aggregateId, expected);
        if (inserted > 0) {
            log.warn("aggregate gap detected: {} {} expected={} received={}", aggregateType, aggregateId, expected,
                    received);
        }
    }
}
