package dev.kaiwen.eventpulse.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.common.errors.RecordTooLargeException;

import dev.kaiwen.eventpulse.entity.OutboxEvent;
import dev.kaiwen.eventpulse.outbox.OutboxStatusService.FailureAction;
import dev.kaiwen.eventpulse.repository.OutboxRepository;

/**
 * 领取式 Relay 的行为：先领取一批（一次性 token），发送成功才标记 published；
 * 失败区分隔离与临时故障，临时故障释放租约结束本轮；数据库标记失败不当成发送失败。
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayStatusTest {

    @Mock
    OutboxRepository outbox;
    @Mock
    OutboxStatusService status;
    @Mock
    KafkaTemplate<String, String> kafka;

    /** 让领取返回 1（领取走 OutboxStatusService 的短事务），并按任意 token 取回给定的批次。 */
    private void claimReturns(OutboxEvent... events) {
        when(status.claimBatch(anyString(), any(Instant.class), any(Instant.class), anyInt())).thenReturn(1);
        when(outbox.findByClaimedByOrderByIdAsc(anyString())).thenReturn(List.of(events));
    }

    private OutboxEvent event(long id, String topic, String messageKey, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.setId(id);
        e.setTopic(topic);
        e.setDedupKey("dedup-" + id);
        e.setMessageKey(messageKey);
        e.setPayload(payload);
        e.setEventType("BOOKING_CREATED");
        e.setCreatedAt(Instant.now());
        return e;
    }

    @Test
    void noClaimableMessagesSendsNothing() {
        when(status.claimBatch(anyString(), any(Instant.class), any(Instant.class), anyInt())).thenReturn(0);
        new OutboxRelay(outbox, kafka, status, 12L, 50, 60).publish();
        verify(outbox, never()).findByClaimedByOrderByIdAsc(anyString());
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void kafkaSuccessMarksPublishedWithMessageKey() {
        OutboxEvent pending = event(1L, "booking-events", "booking:1", "{}");
        claimReturns(pending);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        new OutboxRelay(outbox, kafka, status, 12L, 50, 60).publish();
        // message_key 作为 Kafka key：同一订单的消息进同一 partition 并保序。
        verify(kafka).send("booking-events", "booking:1", "{}");
        verify(status).markPublished(1L);
        verify(status, never()).releaseClaim(anyString(), any());
    }

    @Test
    void missingMessageKeyFallsBackToDedupKey() {
        OutboxEvent legacy = event(1L, "booking-events", null, "{}");
        claimReturns(legacy);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        new OutboxRelay(outbox, kafka, status, 12L, 50, 60).publish();
        verify(kafka).send("booking-events", "dedup-1", "{}");
        verify(status).markPublished(1L);
    }

    @Test
    void kafkaAsyncFailureReleasesClaimAndDoesNotMarkPublished() {
        OutboxEvent pendingEvent = event(2L, "booking-events", "booking:2", "{}");
        claimReturns(pendingEvent);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException("Kafka unavailable")));
        when(status.recordPublishFailure(eq(2L), any())).thenReturn(FailureAction.RETRY_LATER);
        new OutboxRelay(outbox, kafka, status, 12L, 50, 60).publish();
        verify(status, never()).markPublished(any(Long.class));
        // 释放租约，让其他 Worker 可以立刻接手，而不是等租约到期。
        verify(status).releaseClaim(anyString(), eq(2L));
    }

    @Test
    void timeout_stopsRoundWithoutMarking() throws Exception {
        OutboxEvent pendingEvent = event(1L, "booking-events", "booking:1", "{}");
        claimReturns(pendingEvent);
        CompletableFuture<Object> neverCompletes = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn((CompletableFuture) neverCompletes);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, status, 0L, 50, 60);   // 0 second wait => instant timeout
        relay.publish();
        // Future.get with 0 timeout should throw TimeoutException.
        verify(status).recordPublishFailure(eq(1L), any(TimeoutException.class));
        verify(status, never()).markPublished(any(Long.class));
    }

    @Test
    void interruptedRelayStopsRoundWithoutMarking() throws Exception {
        OutboxEvent pendingEvent = event(1L, "booking-events", "booking:1", "{}");
        claimReturns(pendingEvent);
        CompletableFuture<Object> neverCompletes = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn((CompletableFuture) neverCompletes);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, status, 12L, 50, 60);
        Thread current = Thread.currentThread();
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException ignored) {
            }
            current.interrupt();
        });
        relay.publish();
        verify(status, never()).markPublished(any(Long.class));
    }

    @Test
    void permanentFailureIsQuarantinedAndLoopContinues() {
        OutboxEvent bad = event(1L, "booking-events", "booking:1", "{}");
        OutboxEvent good = event(2L, "booking-events", "booking:2", "{}");
        claimReturns(bad, good);
        when(kafka.send(eq("booking-events"), eq("booking:1"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too big")));
        when(kafka.send(eq("booking-events"), eq("booking:2"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(status.recordPublishFailure(eq(1L), any())).thenReturn(FailureAction.QUARANTINED);
        new OutboxRelay(outbox, kafka, status, 12L, 50, 60).publish();
        // 隔离的消息释放租约；同轮继续发送后面的消息。
        verify(status).releaseClaim(anyString(), eq(1L));
        verify(status).markPublished(2L);
    }

    @Test
    void transientFailureStopsRoundAndPreservesOrder() {
        OutboxEvent first = event(1L, "booking-events", "booking:1", "{}");
        OutboxEvent second = event(2L, "booking-events", "booking:2", "{}");
        claimReturns(first, second);
        when(kafka.send(eq("booking-events"), eq("booking:1"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));
        when(status.recordPublishFailure(eq(1L), any())).thenReturn(FailureAction.RETRY_LATER);
        new OutboxRelay(outbox, kafka, status, 12L, 50, 60).publish();
        verify(kafka, never()).send(eq("booking-events"), eq("booking:2"), anyString());
        verify(status, never()).markPublished(any(Long.class));
        verify(status).releaseClaim(anyString(), eq(1L));
    }

    @Test
    void kafkaSuccessThenDatabaseFailureDoesNotRecordFailure() {
        OutboxEvent pendingEvent = event(4L, "booking-events", "booking:4", "{}");
        claimReturns(pendingEvent);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        // Kafka 已确认成功，但数据库标记失败：不能当成发送失败，也不能隔离。
        doThrow(new IllegalStateException("db down")).when(status).markPublished(4L);
        new OutboxRelay(outbox, kafka, status, 12L, 50, 60).publish();
        verify(status, never()).recordPublishFailure(any(Long.class), any());
        verify(status).markPublished(4L);
        // 租约留给下一轮（或到期后其他 Worker 接手），消息不会被标记为失败。
        verify(status, never()).releaseClaim(anyString(), any());
    }

    @Test
    void writerRejectsOversizePayload() throws Exception {
        OutboxWriter writer = new OutboxWriter(outbox, new com.fasterxml.jackson.databind.ObjectMapper());
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 600 * 1024; i++) {
            big.append('x');
        }
        assertThatThrownBy(() -> writer.write("booking-events", "BIG", "booking:1", "big",
                java.util.Map.of("data", big.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("512 KiB");
    }

    @Test
    void claimRunsInShortTransactionWithOneShotToken() {
        // 领取是一条原子数据库语句（在 OutboxStatusService 的事务里执行），
        // 两个 Worker 才不会同时认为自己领取成功；Relay 自己不开长事务。
        when(status.claimBatch(anyString(), any(Instant.class), any(Instant.class), anyInt())).thenReturn(0);
        new OutboxRelay(outbox, kafka, status, 12L, 50, 60).publish();
        org.mockito.ArgumentCaptor<String> token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(status).claimBatch(token.capture(), any(Instant.class), any(Instant.class), anyInt());
        // Relay 为每轮生成一次性 token，领取后按 token 取回本批消息。
        assertThat(token.getValue()).isNotBlank();
        verify(outbox, never()).findByClaimedByOrderByIdAsc(anyString());
    }

    @Test
    void claimTokenFitsClaimedByColumnEvenWithLongContainerHostname() {
        // CI 的 Jenkins Docker agent 主机名是 64 位完整容器 ID，曾把 token 撑到
        // 110 字符、超出 claimed_by VARCHAR(100)，导致每轮领取都失败。
        String workerId = OutboxRelay.workerIdFor("a".repeat(64));
        String token = workerId + ":" + java.util.UUID.randomUUID();
        assertThat(workerId).hasSize(OutboxRelay.MAX_HOSTNAME_LENGTH + 1 + 8);
        assertThat(token.length()).isLessThanOrEqualTo(100);
    }
}
