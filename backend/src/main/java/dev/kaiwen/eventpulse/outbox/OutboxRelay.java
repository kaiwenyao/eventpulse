package dev.kaiwen.eventpulse.outbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single relay worker (MVP). Publishes pending outbox rows strictly ordered by
 * aggregate sequence with an idempotent producer (acks=all).
 *
 * <p>Resource-bulkhead discipline (plan §3.1/§17.3): candidate rows are read
 * on the batch pool, Kafka sends happen synchronously OUTSIDE any database
 * transaction, and only the state flip back to PUBLISHED opens a short
 * transaction on the batch pool. A slow or unreachable broker therefore cannot
 * pin a DB connection for 30 seconds inside a transaction — the classic
 * "queries starve transactions" failure §17.3 warns about.
 *
 * <p>Delivery semantics are unchanged: send-before-mark means a crash between
 * the send and the marking transaction yields a deliberate, cursor-absorbed
 * redelivery ("发布成功后标记前宕机会产生重复，属于设计内行为", plan §9.2). The
 * marking update is conditional on state = PENDING, so a duplicate worker can
 * never un-publish a row.
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

    public OutboxRelay(@Qualifier("batchJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("batchTransactionTemplate") TransactionTemplate tx,
            KafkaTemplate<String, String> kafkaTemplate,
            @org.springframework.beans.factory.annotation.Value("${eventpulse.relay.batch-size:200}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${eventpulse.relay.interval:PT0.5S}")
    public void relayOnce() {
        // 1) Read candidates WITHOUT a transaction: the worker holds no DB
        //    connection while waiting on Kafka. Single worker + aggregate order
        //    + consumer cursor dedup keep the per-aggregate sequence contract.
        List<Row> rows = jdbc.query("""
                SELECT event_id, aggregate_type, aggregate_id, sequence, topic, event_type,
                       schema_version, correlation_id, trace_id, payload::text AS payload
                FROM outbox WHERE state = 'PENDING'
                ORDER BY aggregate_type, aggregate_id, sequence
                LIMIT ?
                """, (rs, i) -> new Row(rs.getObject("event_id", UUID.class), rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class), rs.getLong("sequence"), rs.getString("topic"),
                rs.getString("event_type"), rs.getInt("schema_version"),
                rs.getObject("correlation_id", UUID.class), rs.getString("trace_id"), rs.getString("payload")),
                batchSize);
        if (rows.isEmpty()) {
            return;
        }
        // 2) Publish outside any transaction. A failure leaves the row PENDING
        //    (retry next tick); a crash after send but before step 3) is a
        //    deliberate, cursor-absorbed redelivery - same as before.
        int sent = 0;
        for (Row row : rows) {
            try {
                // Send is synchronous (get) so per-aggregate ordering holds.
                kafkaTemplate.send(row.topic(), row.aggregateId().toString(),
                        OutboxJson.write(envelope(row))).get(30, java.util.concurrent.TimeUnit.SECONDS);
                sent++;
            }
            catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("outbox relay publish failed for {} ({} events already sent): {}", row.eventId(), sent,
                        e.getMessage());
                break; // stop the batch at the first failure; rows stay PENDING
            }
        }
        if (sent == 0) {
            return;
        }
        // 3) Short transaction on the batch pool marks what was published.
        final int delivered = sent;
        Integer published = tx.execute(status -> {
            int marked = 0;
            for (int i = 0; i < delivered; i++) {
                Row row = rows.get(i);
                marked += jdbc.update("UPDATE outbox SET state = 'PUBLISHED', published_at = now(), "
                        + "attempts = attempts + 1 WHERE event_id = ? AND state = 'PENDING'", row.eventId());
            }
            return marked;
        });
        if (published != null && published > 0) {
            log.debug("outbox relay published {} events", published);
        }
    }

    private Map<String, Object> envelope(Row row) {
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
        return envelope;
    }

    public static final class RelayFailedException extends RuntimeException {
        public RelayFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}