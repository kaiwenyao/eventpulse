package dev.kaiwen.eventpulse.outbox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Offset-ordering contract (plan §13/§9.2 and the consumer javadoc): the Kafka
 * offset is acknowledged only AFTER the cursor transaction committed, and a
 * failing transaction propagates WITHOUT any acknowledgement. That is the
 * machine-checkable form of "a kill between the consumer's DB commit and the
 * offset commit does not lose the event": a kill after the commit simply
 * redelivers, and the cursor skips the duplicate.
 */
class NotificationConsumerTest {

    /** PlatformTransactionManager double that counts commit/rollback outcomes. */
    private static final class CountingTxManager extends AbstractPlatformTransactionManager {
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
            // In-memory resource; nothing to begin.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
            rollbacks.incrementAndGet();
        }
    }

    private static ConsumerRecord<String, String> record(long sequence) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "booking.confirmed");
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", "booking");
        envelope.put("aggregateId", UUID.randomUUID().toString());
        envelope.put("aggregateSequence", sequence);
        envelope.put("payload", new LinkedHashMap<String, Object>());
        return new ConsumerRecord<>("booking.events.v1", 0, 0, "key",
                OutboxJson.write(envelope));
    }

    @Test
    void acknowledgesOnlyAfterCommitAndReAcksSkippedDuplicates() {
        CountingTxManager txManager = new CountingTxManager();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        // The consumer's cursor query returns a row; the mapper reads named
        // columns, so a Map-backed stub keeps the stubbing shape-free.
        when(jdbc.<Object>queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return mapper.mapRow(cursorRow(0L, null), 0);
                });
        TransactionTemplate tx = new TransactionTemplate(txManager);

        AtomicInteger commitsAtFirstAck = new AtomicInteger(-1);
        org.springframework.kafka.support.Acknowledgment ack =
                mock(org.springframework.kafka.support.Acknowledgment.class);
        doAnswer(invocation -> {
            commitsAtFirstAck.set(txManager.commits.get());
            return null;
        }).when(ack).acknowledge();

        new NotificationConsumer(jdbc, tx).onMessage(record(1), ack);

        assertThat(txManager.commits.get()).as("the DB transaction committed").isEqualTo(1);
        assertThat(commitsAtFirstAck.get()).as("acknowledge happened after the commit").isEqualTo(1);

        // Redelivery of the same sequence (kill between commit and ack): the
        // cursor skips the duplicate, the skip-decision transaction commits,
        // and the offset is re-acked — never a lost event, never a stuck offset.
        when(jdbc.<Object>queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return mapper.mapRow(cursorRow(1L, null), 0);
                });
        new NotificationConsumer(jdbc, tx).onMessage(record(1), ack);
        assertThat(txManager.commits.get()).isEqualTo(2);
        verify(ack, times(2)).acknowledge();
    }

    @Test
    void aFailingTransactionIsNeverAcknowledged() {
        CountingTxManager txManager = new CountingTxManager();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.<Object>queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        TransactionTemplate tx = new TransactionTemplate(txManager);

        org.springframework.kafka.support.Acknowledgment ack =
                mock(org.springframework.kafka.support.Acknowledgment.class);
        assertThatThrownBy(() -> new NotificationConsumer(jdbc, tx).onMessage(record(1), ack))
                .isInstanceOf(Exception.class);
        // No commit, no ack: the broker redelivers, the event is not lost.
        assertThat(txManager.commits.get()).isZero();
        verify(ack, never()).acknowledge();
    }

    @Test
    void malformedEnvelopeIsLeftForTheDltErrorHandler() {
        CountingTxManager txManager = new CountingTxManager();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        org.springframework.kafka.support.Acknowledgment ack =
                mock(org.springframework.kafka.support.Acknowledgment.class);
        ConsumerRecord<String, String> poison = new ConsumerRecord<>("booking.events.v1", 0, 0, "key",
                "not-a-json-envelope");
        assertThatThrownBy(() -> new NotificationConsumer(jdbc, tx).onMessage(poison, ack))
                .isInstanceOf(IllegalStateException.class);
        verify(ack, never()).acknowledge();
        assertThat(txManager.commits.get()).isZero();
    }

    /** Minimal cursor-row double: the mapper reads last_sequence/last_event_id. */
    private static java.sql.ResultSet cursorRow(Long lastSequence, UUID lastEventId) throws Exception {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getLong("last_sequence")).thenReturn(lastSequence == null ? 0L : lastSequence);
        when(rs.getObject("last_event_id", UUID.class)).thenReturn(lastEventId);
        return rs;
    }
}