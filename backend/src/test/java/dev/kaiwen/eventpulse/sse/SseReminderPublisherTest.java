package dev.kaiwen.eventpulse.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class SseReminderPublisherTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SseReminderPublisher publisher = new SseReminderPublisher(new ObjectMapper());

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesLightweightReminderWithoutActiveTransaction() throws Exception {
        publisher.setRedis(redis);
        publisher.remindBooking(7L, "BOOKING_CREATED", "BOOKING_CREATED:7");

        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(SseReminder.REDIS_CHANNEL), body.capture());
        JsonNode json = new ObjectMapper().readTree(body.getValue());
        assertThat(json.get("bookingId").asLong()).isEqualTo(7L);
        assertThat(json.get("type").asText()).isEqualTo("BOOKING_CREATED");
        assertThat(json.get("eventId").asText()).isEqualTo("BOOKING_CREATED:7");
        assertThat(json.get("occurredAt").asText()).isNotBlank();
    }

    @Test
    void reminderIsPublishedOnlyAfterCommit() {
        publisher.setRedis(redis);
        TransactionSynchronizationManager.initSynchronization();

        // 事务进行中：注册回调但不发布（中途发布会出现「提醒到了数据还没提交」）。
        publisher.remindBooking(7L, "BOOKING_CREATED", "BOOKING_CREATED:7");
        verify(redis, never()).convertAndSend(anyString(), anyString());

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        verify(redis).convertAndSend(eq(SseReminder.REDIS_CHANNEL), anyString());
    }

    @Test
    void missingBookingIdIsIgnored() {
        publisher.setRedis(redis);
        publisher.remindBooking(null, "X", "k");
        verify(redis, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void withoutRedisThePublisherIsSilentlyDisabled() {
        // Redis 未启用（redis-enabled=false）：不发也不报错，业务流程不受影响。
        assertThatCode(() -> publisher.remindBooking(7L, "BOOKING_CREATED", "k")).doesNotThrowAnyException();
    }

    @Test
    void publishFailureIsSwallowedSoBusinessIsNotAffected() {
        publisher.setRedis(redis);
        doThrow(new IllegalStateException("redis down")).when(redis).convertAndSend(anyString(), anyString());
        assertThatCode(() -> publisher.remindBooking(7L, "BOOKING_CREATED", "k")).doesNotThrowAnyException();
    }
}
