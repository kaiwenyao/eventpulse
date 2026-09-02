package dev.kaiwen.eventpulse.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

import com.fasterxml.jackson.databind.ObjectMapper;

class SseEventSubscriberTest {

    private final SseNotificationService notifications = mock(SseNotificationService.class);
    private final SseEventSubscriber subscriber = new SseEventSubscriber(notifications, new ObjectMapper());

    @AfterEach
    void resetTxState() {
        // 发布者测试会开启事务同步；这里兜底清理，避免污染其他测试。
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Message message(String json) {
        return new DefaultMessage(SseReminder.REDIS_CHANNEL.getBytes(StandardCharsets.UTF_8),
                json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void pushesReminderToLocalConnections() {
        subscriber.onMessage(message(
                "{\"eventId\":\"evt-1\",\"type\":\"BOOKING_UPDATED\",\"bookingId\":7,\"occurredAt\":\"2026-09-02T10:20:30Z\"}"), new byte[0]);
        verify(notifications).broadcast(argThat(r ->
                "evt-1".equals(r.eventId()) && "BOOKING_UPDATED".equals(r.type())
                        && r.bookingId() == 7L && "2026-09-02T10:20:30Z".equals(r.occurredAt())));
    }

    @Test
    void duplicateEventIdIsIgnored() {
        String json = "{\"eventId\":\"evt-1\",\"type\":\"BOOKING_UPDATED\",\"bookingId\":7,\"occurredAt\":\"x\"}";
        subscriber.onMessage(message(json), new byte[0]);
        subscriber.onMessage(message(json), new byte[0]);
        // 重放同一条提醒只推一次：前端最多多刷新一次，业务数据不受影响。
        verify(notifications, times(1)).broadcast(argThat(r -> true));
    }

    @Test
    void missingEventIdFallsBackToTypeAndBooking() {
        subscriber.onMessage(message("{\"type\":\"BOOKING_UPDATED\",\"bookingId\":9}"), new byte[0]);
        verify(notifications).broadcast(argThat(r -> "BOOKING_UPDATED:9".equals(r.eventId()) && r.bookingId() == 9L));
    }

    @Test
    void malformedPayloadDoesNotBreakTheSubscription() {
        subscriber.onMessage(message("not-json"), new byte[0]);
        subscriber.onMessage(message("{\"type\":\"BOOKING_UPDATED\"}"), new byte[0]);
        verifyNoInteractions(notifications);
    }

    @Test
    void dedupWindowIsBounded() {
        // 超过窗口后允许重新推送：极端重放不会把内存撑大，也不会永久吞掉提醒。
        for (int i = 0; i < SseEventSubscriber.MAX_REMEMBERED_IDS + 10; i++) {
            subscriber.onMessage(message("{\"eventId\":\"evt-" + i + "\",\"type\":\"X\",\"bookingId\":1}"), new byte[0]);
        }
        assertThat(subscriber.recentEventIdsSize()).isLessThanOrEqualTo(SseEventSubscriber.MAX_REMEMBERED_IDS);
    }
}
