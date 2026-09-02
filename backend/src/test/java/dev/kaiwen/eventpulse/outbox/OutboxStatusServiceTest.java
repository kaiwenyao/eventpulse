package dev.kaiwen.eventpulse.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;

import dev.kaiwen.eventpulse.entity.OutboxEvent;
import dev.kaiwen.eventpulse.outbox.OutboxStatusService.FailureAction;
import dev.kaiwen.eventpulse.repository.OutboxRepository;

@ExtendWith(MockitoExtension.class)
class OutboxStatusServiceTest {

    @Mock
    OutboxRepository outbox;

    private OutboxStatusService service() {
        return new OutboxStatusService(outbox);
    }

    private OutboxEvent entityWithAttempts(int attempts) {
        OutboxEvent e = new OutboxEvent();
        e.setId(9L);
        e.setPublishAttempts(attempts);
        return e;
    }

    @Test
    void markPublishedDelegatesToConditionalUpdate() {
        when(outbox.markPublished(org.mockito.ArgumentMatchers.eq(9L), any(Instant.class))).thenReturn(1);
        service().markPublished(9L);
        verify(outbox).markPublished(org.mockito.ArgumentMatchers.eq(9L), any(Instant.class));

        when(outbox.markPublished(org.mockito.ArgumentMatchers.eq(9L), any(Instant.class))).thenReturn(0);
        service().markPublished(9L); // 0 rows: no exception, no retry
    }

    @Test
    void permanentFailureQuarantinesImmediately() {
        FailureAction action = service().recordPublishFailure(9L, new RecordTooLargeException("too big"));
        assertThat(action).isEqualTo(FailureAction.QUARANTINED);
        verify(outbox).recordFailure(org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.contains("too big"), any(Instant.class));
    }

    @Test
    void serializationExceptionAlsoQuarantines() {
        FailureAction action = service().recordPublishFailure(9L, new SerializationException("bad payload"));
        assertThat(action).isEqualTo(FailureAction.QUARANTINED);
        verify(outbox).recordFailure(org.mockito.ArgumentMatchers.eq(9L), anyString(), any(Instant.class));
    }

    @Test
    void invalidTopicQuarantinesImmediately() {
        // 计划：Outbox 行里保存了非法 topic 属于第一类，立刻隔离。
        FailureAction action = service().recordPublishFailure(9L,
                new org.apache.kafka.common.errors.InvalidTopicException("bad topic"));
        assertThat(action).isEqualTo(FailureAction.QUARANTINED);
        verify(outbox).recordFailure(org.mockito.ArgumentMatchers.eq(9L), anyString(), any(Instant.class));
    }

    @Test
    void wrapsExecutionExceptionBeforeClassifying() {
        assertThat(service().recordPublishFailure(9L, new ExecutionException(new SerializationException("inner"))))
                .isEqualTo(FailureAction.QUARANTINED);
        verify(outbox).recordFailure(org.mockito.ArgumentMatchers.eq(9L), anyString(), any(Instant.class));
    }

    @Test
    void transientFailureNeverQuarantines() {
        // 即使之前已经失败很多次，明确的临时故障也不因为次数多被隔离。
        FailureAction action = service().recordPublishFailure(9L, new RetriableException("temporary") {
        });
        assertThat(action).isEqualTo(FailureAction.RETRY_LATER);
        verify(outbox).recordFailure(org.mockito.ArgumentMatchers.eq(9L), anyString(), isNull());

        FailureAction kafkaDown = service().recordPublishFailure(9L, new KafkaException("cluster down"));
        assertThat(kafkaDown).isEqualTo(FailureAction.RETRY_LATER);
        verify(outbox, org.mockito.Mockito.times(2))
                .recordFailure(org.mockito.ArgumentMatchers.eq(9L), anyString(), isNull());
    }

    @Test
    void unknownFailureQuarantinesAfterLimit() {
        // attempts 从 4 → 5: 刚好达到上限，写 failed_at。
        when(outbox.findById(9L)).thenReturn(Optional.of(entityWithAttempts(4)));
        FailureAction action = service().recordPublishFailure(9L, new RuntimeException("mystery"));
        assertThat(action).isEqualTo(FailureAction.QUARANTINED);
        verify(outbox).recordFailure(org.mockito.ArgumentMatchers.eq(9L), anyString(), any(Instant.class));
    }

    @Test
    void unknownFailureStaysPendingUnderLimit() {
        when(outbox.findById(9L)).thenReturn(Optional.of(entityWithAttempts(1)));
        FailureAction action = service().recordPublishFailure(9L, new RuntimeException("mystery"));
        assertThat(action).isEqualTo(FailureAction.RETRY_LATER);
        verify(outbox).recordFailure(org.mockito.ArgumentMatchers.eq(9L), anyString(), isNull());
    }
}