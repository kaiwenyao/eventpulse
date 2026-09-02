package dev.kaiwen.eventpulse.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.entity.Notification;
import dev.kaiwen.eventpulse.repository.ConsumedEventRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;
import dev.kaiwen.eventpulse.service.InteractionService;

@ExtendWith(MockitoExtension.class)
class BookingConsumerTest {

    @Mock
    ConsumedEventRepository consumedEvents;
    @Mock
    NotificationRepository notifications;
    @Mock
    InteractionService interactionService;

    private BookingConsumer consumer() {
        return new BookingConsumer(consumedEvents, notifications, interactionService, new ObjectMapper());
    }

    private static final String BOOKING_CREATED = """
            {"type":"BOOKING_CREATED","dedupKey":"BOOKING_CREATED:10","userId":3,"eventId":20,"bookingId":10,
             "quantity":2,"title":"预订成功","message":"你已预订「夜」2 张票"}
            """;

    @Test
    void firstTimeCreatesNotificationAndBookInteraction() {
        when(consumedEvents.tryInsert(eq(BookingConsumer.CONSUMER_GROUP), eq("BOOKING_CREATED:10"))).thenReturn(1);
        consumer().onMessage(BOOKING_CREATED);
        verify(notifications).save(any(Notification.class));
        verify(interactionService).record(3L, 20L, "BOOK", 2);
    }

    @Test
    void bookInteractionCarriesTicketQuantity() {
        // 一次订 4 张：BOOK interaction 仍只记 1 条，但张数要传给统计（tickets +4）。
        String fourTickets = """
                {"type":"BOOKING_CREATED","dedupKey":"BOOKING_CREATED:11","userId":3,"eventId":20,"bookingId":11,
                 "quantity":4,"title":"预订成功","message":"你已预订「夜」4 张票"}
                """;
        when(consumedEvents.tryInsert(eq(BookingConsumer.CONSUMER_GROUP), eq("BOOKING_CREATED:11"))).thenReturn(1);
        consumer().onMessage(fourTickets);
        verify(notifications).save(any(Notification.class));
        verify(interactionService).record(3L, 20L, "BOOK", 4);
    }

    @Test
    void bookingWithoutQuantityGoesToDlt() {
        // 缺少 quantity：无法统计张数，抛异常由 Error Handler 进 DLT，不写半成品互动。
        String noQuantity = """
                {"type":"BOOKING_CREATED","dedupKey":"BOOKING_CREATED:12","userId":3,"eventId":20,"bookingId":12,
                 "title":"预订成功"}
                """;
        when(consumedEvents.tryInsert(eq(BookingConsumer.CONSUMER_GROUP), eq("BOOKING_CREATED:12"))).thenReturn(1);
        assertThatThrownBy(() -> consumer().onMessage(noQuantity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quantity");
        verify(interactionService, never()).record(any(), any(), anyString());
    }

    @Test
    void bookingWithNonPositiveQuantityGoesToDlt() {
        String zeroQuantity = """
                {"type":"BOOKING_CREATED","dedupKey":"BOOKING_CREATED:13","userId":3,"eventId":20,"bookingId":13,
                 "quantity":0,"title":"预订成功"}
                """;
        when(consumedEvents.tryInsert(eq(BookingConsumer.CONSUMER_GROUP), eq("BOOKING_CREATED:13"))).thenReturn(1);
        assertThatThrownBy(() -> consumer().onMessage(zeroQuantity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quantity");
        verify(interactionService, never()).record(any(), any(), anyString());
    }

    @Test
    void duplicateMessageIsIgnored() {
        when(consumedEvents.tryInsert(eq(BookingConsumer.CONSUMER_GROUP), anyString())).thenReturn(0);
        consumer().onMessage(BOOKING_CREATED);
        verify(notifications, never()).save(any());
        verify(interactionService, never()).record(any(), any(), any());
    }

    @Test
    void cancelledWritesCancelInteraction() {
        String cancelled = """
                {"type":"BOOKING_CANCELLED","dedupKey":"BOOKING_CANCELLED:11","userId":3,"eventId":20,"bookingId":11,
                 "title":"预订已取消"}
                """;
        when(consumedEvents.tryInsert(eq(BookingConsumer.CONSUMER_GROUP), eq("BOOKING_CANCELLED:11"))).thenReturn(1);
        consumer().onMessage(cancelled);
        verify(interactionService).record(3L, 20L, "CANCEL");
    }

    @Test
    void eventCancelledCreatesNotificationOnly() {
        String cancelled = """
                {"type":"EVENT_CANCELLED","dedupKey":"EVENT_CANCELLED:5:3","userId":3,"eventId":5,"bookingId":9}
                """;
        when(consumedEvents.tryInsert(eq(BookingConsumer.CONSUMER_GROUP), eq("EVENT_CANCELLED:5:3"))).thenReturn(1);
        consumer().onMessage(cancelled);
        verify(notifications).save(any(Notification.class));
        verify(interactionService, never()).record(any(), any(), any());
    }

    @Test
    void bookingMessageMissingFieldsGoesToDltViaException() {
        // 缺少 userId / eventId：不能写不完整的 interaction，抛出异常由 Error Handler 进 DLT。
        // 通知虽然已保存，但整个事务回滚，不会留下半成品。
        when(consumedEvents.tryInsert(eq(BookingConsumer.CONSUMER_GROUP), anyString())).thenReturn(1);
        assertThatThrownBy(() -> consumer().onMessage(
                "{\"type\":\"BOOKING_CREATED\",\"dedupKey\":\"BOOKING_CREATED:1\",\"bookingId\":1}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少");
        verify(interactionService, never()).record(any(), any(), any());
    }

    @Test
    void unparseableMessageThrowsInsteadOfBeingSwallowed() {
        assertThatThrownBy(() -> consumer().onMessage("not-json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void interactionFailurePropagatesForRetry() {
        when(consumedEvents.tryInsert(eq(BookingConsumer.CONSUMER_GROUP), eq("BOOKING_CREATED:10"))).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("metric insert failed"))
                .when(interactionService).record(3L, 20L, "BOOK", 2);
        assertThatThrownBy(() -> consumer().onMessage(BOOKING_CREATED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metric insert failed");
    }
}