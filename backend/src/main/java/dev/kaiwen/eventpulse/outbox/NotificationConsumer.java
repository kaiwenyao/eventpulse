package dev.kaiwen.eventpulse.outbox;

import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.config.KafkaConfig;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reference consumer. In one local transaction it: checks/advances the
 * per-aggregate cursor, applies the side effect (notifications), records the
 * event id and writes new outbox rows when needed. Duplicates (sequence &lt;=
 * cursor) are skipped; a gap (sequence &gt; cursor + 1) blocks only that
 * aggregate and is recorded for admin resolution.
 *
 * <p>Offset ordering guarantee: the listener is deliberately NOT
 * {@code @Transactional} so it fully controls the boundary. The DB work
 * (cursor check/advance, side effect, gap record) runs in an explicit
 * transaction template; {@code acknowledgment.acknowledge()} is invoked only
 * AFTER that transaction has committed. A kill between DB commit and offset
 * commit is therefore safe: the redelivered event is absorbed by the cursor
 * (sequence &lt;= lastSeq is skipped), never lost. A failure inside the DB
 * transaction propagates without any acknowledgement, so the broker redelivers
 * instead of advancing past a lost event. This is the exact direction the
 * plan's §13 matrix requires ("kill between consumer DB commit and offset").
 */
@Component
public class NotificationConsumer {

    public static final String CONSUMER_ID = "notification-consumer";

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    /** What the committed transaction decided; acknowledge follows the commit. */
    private enum Outcome { SKIP_DUPLICATE, RECORD_GAP, APPLY }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public NotificationConsumer(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @KafkaListener(topics = { OutboxWriter.TOPIC_BOOKING, OutboxWriter.TOPIC_CATALOGUE,
            OutboxWriter.TOPIC_INTERACTION, OutboxWriter.TOPIC_NOTIFICATION_COMMANDS }, concurrency = "1")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        KafkaConfig.ParsedEnvelope envelope;
        try {
            envelope = KafkaConfig.ParsedEnvelope.parse(record);
        }
        catch (Exception e) {
            // Malformed envelope: let the error handler route it to the DLT.
            throw new IllegalStateException("unparseable envelope on " + record.topic(), e);
        }
        // Acknowledge strictly after the DB transaction commits. The offset
        // must never overtake the committed cursor: a crash here (commit done,
        // offset not yet acked) re-delivers the record and the cursor skips it.
        tx.execute(status -> {
            process(envelope);
            return null;
        });
        acknowledgment.acknowledge();
    }

    private Outcome process(KafkaConfig.ParsedEnvelope envelope) {
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
            return Outcome.SKIP_DUPLICATE;
        }
        if (sequence > cursor.lastSeq() + 1) {
            recordGap(aggregateType, aggregateId, cursor.lastSeq() + 1, sequence, envelope.eventUuid());
            // Do not advance the cursor: the aggregate stays blocked until an
            // admin resolves the gap. Later events of the same aggregate keep
            // queueing; other aggregates continue unaffected.
            return Outcome.RECORD_GAP;
        }

        applySideEffect(aggregateType, envelope);
        jdbc.update("""
                UPDATE consumer_cursors SET last_sequence = ?, last_event_id = ?, updated_at = now()
                WHERE consumer = ? AND aggregate_type = ? AND aggregate_id = ?
                """, sequence, envelope.eventUuid(), CONSUMER_ID, aggregateType, aggregateId);
        return Outcome.APPLY;
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