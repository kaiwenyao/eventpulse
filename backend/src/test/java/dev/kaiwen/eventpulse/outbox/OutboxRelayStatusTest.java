package dev.kaiwen.eventpulse.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;

import dev.kaiwen.eventpulse.entity.OutboxEvent;
import dev.kaiwen.eventpulse.outbox.OutboxStatusService.FailureAction;
import dev.kaiwen.eventpulse.repository.OutboxRepository;

@ExtendWith(MockitoExtension.class)
class OutboxRelayStatusTest {

    @Mock
    OutboxRepository outbox;
    @Mock
    OutboxStatusService status;
    @Mock
    KafkaTemplate<String, String> kafka;

    private OutboxEvent event(long id, String topic, String dedupKey, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.setId(id);
        e.setTopic(topic);
        e.setDedupKey(dedupKey);
        e.setPayload(payload);
        e.setEventType("BOOKING_CREATED");
        e.setCreatedAt(Instant.now());
        return e;
    }

    @Test
    void kafkaSuccessMarksPublished() {
        OutboxEvent pending = event(1L, "booking-events", "k1", "{}");
        when(outbox.findTop50ByPublishedAtIsNullAndFailedAtIsNullOrderByIdAsc()).thenReturn(List.of(pending));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, status, 12L);
        relay.publish();
        verify(status).markPublished(1L);
    }

    @Test
    void kafkaAsyncFailureDoesNotMarkPublished() {
        OutboxEvent pendingEvent = event(2L, "booking-events", "k2", "{}");
        when(outbox.findTop50ByPublishedAtIsNullAndFailedAtIsNullOrderByIdAsc()).thenReturn(List.of(pendingEvent));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException("Kafka unavailable")));
        when(status.recordPublishFailure(eq(2L), any())).thenReturn(FailureAction.RETRY_LATER);
        new OutboxRelay(outbox, kafka, status, 12L).publish();
        verify(status, never()).markPublished(any(Long.class));
    }

    @Test
    void timeout_stopsRoundWithoutMarking() throws Exception {
        OutboxEvent pendingEvent = event(1L, "booking-events", "k1", "{}");
        when(outbox.findTop50ByPublishedAtIsNullAndFailedAtIsNullOrderByIdAsc()).thenReturn(List.of(pendingEvent));
        CompletableFuture<Object> neverCompletes = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn((CompletableFuture) neverCompletes);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, status, 0L);   // 0 second wait => instant timeout
        relay.publish();
        // Future.get with 0 timeout should throw TimeoutException.
        verify(status).recordPublishFailure(eq(1L), any(TimeoutException.class));
        verify(status, never()).markPublished(any(Long.class));
    }

    @Test
    void permanentFailureIsQuarantinedAndLoopContinues() {
        OutboxEvent bad = event(1L, "booking-events", "bad", "{}");
        OutboxEvent good = event(2L, "booking-events", "good", "{}");
        when(outbox.findTop50ByPublishedAtIsNullAndFailedAtIsNullOrderByIdAsc()).thenReturn(List.of(bad, good));
        when(kafka.send(eq("booking-events"), eq("bad"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too big")));
        when(kafka.send(eq("booking-events"), eq("good"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(status.recordPublishFailure(eq(1L), any())).thenReturn(FailureAction.QUARANTINED);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, status, 12L);
        relay.publish();
        // Second message still sent in the same round
        verify(status).markPublished(2L);
    }

    @Test
    void transientFailureStopsRoundAndPreservesOrder() {
        OutboxEvent first = event(1L, "booking-events", "a", "{}");
        OutboxEvent second = event(2L, "booking-events", "b", "{}");
        when(outbox.findTop50ByPublishedAtIsNullAndFailedAtIsNullOrderByIdAsc()).thenReturn(List.of(first, second));
        when(kafka.send(eq("booking-events"), eq("a"), anyString())).thenReturn(CompletableFuture.failedFuture(
                new KafkaException("broker down")));
        when(status.recordPublishFailure(eq(1L), any())).thenReturn(FailureAction.RETRY_LATER);
        new OutboxRelay(outbox, kafka, status, 12L).publish();
        verify(kafka, never()).send(eq("booking-events"), eq("b"), anyString());
        verify(status, never()).markPublished(any(Long.class));
    }

    @Test
    void kafkaSuccessThenDatabaseFailureDoesNotRecordFailure() {
        OutboxEvent pendingEvent = event(4L, "booking-events", "k4", "{}");
        when(outbox.findTop50ByPublishedAtIsNullAndFailedAtIsNullOrderByIdAsc()).thenReturn(List.of(pendingEvent));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        // Kafka 已确认成功，但数据库标记失败：不能当成发送失败，也不能隔离。
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(status).markPublished(4L);
        new OutboxRelay(outbox, kafka, status, 12L).publish();
        verify(status, never()).recordPublishFailure(any(Long.class), any());
        verify(status).markPublished(4L);
    }

    @Test
    void writerRejectsOversizePayload() throws Exception {
        OutboxWriter writer = new OutboxWriter(outbox, new com.fasterxml.jackson.databind.ObjectMapper());
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 600 * 1024; i++) {
            big.append('x');
        }
        assertThatThrownBy(() -> writer.write("booking-events", "BIG", "big",
                java.util.Map.of("data", big.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("512 KiB");
    }

    @Test
    void oldestPendingAgeIsZeroWhenQueueEmpty() {
        when(outbox.secondsSinceOldestPending()).thenReturn(null);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, status, 12L);
        // 没有待发送消息时不能返回 null（Map.of 会 NPE），应返回 0。
        assertThat(relay.oldestPendingAgeSeconds()).isEqualTo(0d);
    }

    @Test
    void oldestPendingAgeReturnsValueWhenQueueHasMessages() {
        when(outbox.secondsSinceOldestPending()).thenReturn(42.5d);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, status, 12L);
        assertThat(relay.oldestPendingAgeSeconds()).isEqualTo(42.5d);
    }
}