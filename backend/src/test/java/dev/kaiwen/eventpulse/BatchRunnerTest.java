package dev.kaiwen.eventpulse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.batch.BookingCancellationBatch;
import dev.kaiwen.eventpulse.batch.ExpiryScheduler;
import dev.kaiwen.eventpulse.service.BookingTransitions;
import dev.kaiwen.eventpulse.exception.ApiException;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Expiry + cancellation batch runners: SKIP LOCKED pagination and isolation. */
class BatchRunnerTest {

    @Test
    void expirySchedulerExpiresClaimedBookingsAndIsolatesFailures() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BookingTransitions transitions = mock(BookingTransitions.class);
        ExpiryScheduler scheduler = new ExpiryScheduler(jdbc, transitions);

        UUID ok = UUID.randomUUID();
        UUID racing = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of(ok, racing, failing));
        when(transitions.expireBooking(ok)).thenReturn(true);
        when(transitions.expireBooking(racing)).thenReturn(false);
        when(transitions.expireBooking(failing)).thenThrow(new IllegalStateException("db glitch"));

        scheduler.scan(); // must not throw despite the failing booking

        org.mockito.Mockito.verify(transitions).expireBooking(ok);
        org.mockito.Mockito.verify(transitions).expireBooking(failing);
    }

    @Test
    void expirySchedulerHandlesEmptyClaim() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BookingTransitions transitions = mock(BookingTransitions.class);
        when(jdbc.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of());
        new ExpiryScheduler(jdbc, transitions).scan();
        org.mockito.Mockito.verifyNoInteractions(transitions);
    }

    @Test
    void cancellationBatchPagesWithCursorAndCountsFailures() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BookingTransitions transitions = mock(BookingTransitions.class);
        BookingCancellationBatch batch = new BookingCancellationBatch(jdbc, transitions);

        UUID event = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // pagination loop: page 1 -> [first], page 2 -> [second], page 3 -> empty
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class)))
                .thenReturn(List.of(first))
                .thenReturn(List.of(second))
                .thenReturn(List.of());
        when(transitions.cancel(any(), eq(first), eq(true), anyString())).thenReturn(true);
        when(transitions.cancel(any(), eq(second), eq(true), anyString()))
                .thenThrow(new ApiException(dev.kaiwen.eventpulse.exception.ErrorCode.CONFLICT, "used ticket"));

        BookingCancellationBatch.BatchResult result = batch.runForEvent(event);
        assertThat(result.cancelled()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }
}
