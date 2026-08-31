package com.eventpulse.outbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single relay worker (MVP). Publishes pending outbox rows strictly ordered by
 * aggregate sequence with an idempotent producer (acks=all). Publishing and
 * marking PUBLISHED happen in the same transaction; a crash after publish but
 * before the commit yields a deliberate redelivery that consumers dedupe.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    public record Row(UUID eventId, String aggregateType, UUID aggregateId, long sequence, String topic,
                      String eventType, int schemaVersion, UUID correlationId, String traceId, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafkaTemplate,
            @Value("${eventpulse.relay.batch-size:200}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${eventpulse.relay.interval:PT0.5S}")
    public void relayOnce() {
        int published = tx.execute(status -> {
            List<Row> rows = jdbc.query("""
                    SELECT event_id, aggregate_type, aggregate_id, sequence, topic, event_type,
                           schema_version, correlation_id, trace_id, payload::text AS payload
                    FROM outbox WHERE state = 'PENDING'
                    ORDER BY aggregate_type, aggregate_id, sequence
                    LIMIT ? FOR UPDATE SKIP LOCKED
                    """, (rs, i) -> new Row(rs.getObject("event_id", UUID.class), rs.getString("aggregate_type"),
                    rs.getObject("aggregate_id", UUID.class), rs.getLong("sequence"), rs.getString("topic"),
                    rs.getString("event_type"), rs.getInt("schema_version"),
                    rs.getObject("correlation_id", UUID.class), rs.getString("trace_id"), rs.getString("payload")),
                    batchSize);
            for (Row row : rows) {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("eventId", row.eventId());
                envelope.put("eventType", row.eventType());
                envelope.put("schemaVersion", row.schemaVersion());
                envelope.put("aggregateType", row.aggregateType());
                envelope.put("aggregateId", row.aggregateId());
                envelope.put("aggregateSequence", row.sequence());
                envelope.put("correlationId", row.correlationId());
                envelope.put("traceId", row.traceId());
                envelope.put("occurredAt", Instant.now().toString());
                envelope.put("producer", Envelope.PRODUCER);
                envelope.put("payload", OutboxJson.mapper().readValue(row.payload(), Map.class));
                try {
                    // Send is synchronous (get) so per-aggregate ordering holds.
                    kafkaTemplate.send(row.topic(), row.aggregateId().toString(),
                            OutboxJson.write(envelope)).get(30, java.util.concurrent.TimeUnit.SECONDS);
                }
                catch (Exception e) {
                    throw new RelayFailedException("kafka publish failed for " + row.eventId(), e);
                }
            }
            for (Row row : rows) {
                jdbc.update("UPDATE outbox SET state = 'PUBLISHED', published_at = now(), "
                        + "attempts = attempts + 1 WHERE event_id = ?", row.eventId());
            }
            return rows.size();
        });
        if (published > 0) {
            log.debug("outbox relay published {} events", published);
        }
    }

    public static final class RelayFailedException extends RuntimeException {
        public RelayFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
