package dev.kaiwen.eventpulse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.service.BookingTransitions;
import dev.kaiwen.eventpulse.service.impl.BookingTransitionsImpl;
import dev.kaiwen.eventpulse.common.DbClock;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.service.TicketIssuer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BookingTransitions terminal-state branches that the integration suite does
 * not reach directly: idempotent cancel/expire no-ops.
 */
class BookingTransitionsTest {

    private JdbcTemplate jdbc;
    private BookingTransitions transitions;
    private OutboxWriter outbox;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        outbox = mock(OutboxWriter.class);
        DbClock clock = mock(DbClock.class);
        when(clock.now()).thenReturn(Instant.now());
        TicketIssuer issuer = mock(TicketIssuer.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        transitions = new BookingTransitionsImpl(jdbc, outbox, clock, issuer, events);
    }

    private BookingTransitions.BookingRow row(UUID bookingId, String status, String refundState) {
        return new BookingTransitions.BookingRow(bookingId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2, status, "NONE", refundState, 10000L, "CNY",
                "{\"cancellable\": true, \"cancellationDeadlineHoursBeforeStart\": 0, \"resaleAllowed\": false, \"version\": 1}",
                UUID.randomUUID(), Instant.now().plusSeconds(3600), 1);
    }

    private void lockReturns(BookingTransitions.BookingRow booking) {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(booking.id()))).thenReturn(List.of(booking));
    }

    @Test
    void cancelIsIdempotentForTerminalStatuses() {
        UUID bookingId = UUID.randomUUID();
        for (String terminal : List.of("CANCELLATION_PENDING", "CANCELLED", "CANCELLED_BEFORE_PAYMENT",
                "EXPIRED")) {
            lockReturns(row(bookingId, terminal, "NONE"));
            assertThat(transitions.cancel(UUID.randomUUID(), bookingId, false, "user")).isFalse();
        }
        verify(outbox, never()).append(anyString(), any(UUID.class), anyString(), eq("booking.cancelled"),
                any(Map.class));
    }

    @Test
    void expireBookingIsNoOpWhenStillWithinWindow() {
        UUID bookingId = UUID.randomUUID();
        BookingTransitions.BookingRow booking = new BookingTransitions.BookingRow(bookingId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, "PAYMENT_PENDING", "NONE", "NONE",
                10000L, "CNY", "{}", null, Instant.now().plusSeconds(3600), 1);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(bookingId))).thenReturn(List.of(booking));
        assertThat(transitions.expireBooking(bookingId)).isFalse();
        verify(jdbc, never()).update(contains("status = 'EXPIRED'"), any(Object[].class));
    }

    @Test
    void expireBookingIsNoOpWhenNotPaymentPending() {
        UUID bookingId = UUID.randomUUID();
        lockReturns(row(bookingId, "CONFIRMED", "NONE"));
        assertThat(transitions.expireBooking(bookingId)).isFalse();
    }
}
