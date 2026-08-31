package com.eventpulse;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eventpulse.booking.BookingTransitions;
import com.eventpulse.common.DbClock;
import com.eventpulse.outbox.OutboxWriter;
import com.eventpulse.ticketing.TicketIssuer;

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
 * BookingTransitions terminal-state machine branches that the integration
 * suite does not reach directly: void/no-op capture, late-capture compensation
 * and idempotent re-runs. Driven through mocked JdbcTemplate so the branching
 * and emitted events are observable without a database.
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
        transitions = new BookingTransitions(jdbc, outbox, clock, issuer, events);
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
    void completeVoidMarksIntentVoidedAndEchoesOutcome() {
        UUID bookingId = UUID.randomUUID();
        lockReturns(row(bookingId, "CANCELLED_BEFORE_PAYMENT", "NONE"));
        String result = transitions.completeVoid(bookingId, "vd-pi", "pi", "SUCCESS");
        assertThat(result).isEqualTo("void_SUCCESS");
        verify(jdbc).update(contains("state = 'VOIDED'"), eq(bookingId), eq("pi"));
    }

    @Test
    void completeVoidReturnsBookingMissingWhenBookingAbsent() {
        UUID bookingId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(bookingId))).thenReturn(List.of());
        assertThat(transitions.completeVoid(bookingId, "vd-pi", "pi", "SUCCESS")).isEqualTo("booking_missing");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void convertVoidToRefundCreatesRefundWhenNoPriorCommand() {
        UUID bookingId = UUID.randomUUID();
        lockReturns(row(bookingId, "CANCELLED", "NONE"));
        UUID commandId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        // First query call is the INSERT...RETURNING for the command.
        when(jdbc.query(contains("INSERT INTO commands"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(commandId));
        when(jdbc.queryForObject(contains("INSERT INTO refunds"), eq(UUID.class), any(Object[].class)))
                .thenReturn(refundId);

        String result = transitions.convertVoidToRefund(bookingId, "pi", 20000L, "CNY");
        assertThat(result).isEqualTo("refund_created");
        verify(outbox).append(eq("booking"), eq(bookingId), anyString(), eq("booking.late_capture_compensated"),
                any(Map.class));
    }

    @Test
    void convertVoidToRefundIsIdempotentWhenCommandAlreadyExists() {
        UUID bookingId = UUID.randomUUID();
        lockReturns(row(bookingId, "CANCELLED", "PENDING"));
        // NOT EXISTS guard returned nothing -> already compensated.
        when(jdbc.query(contains("INSERT INTO commands"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        assertThat(transitions.convertVoidToRefund(bookingId, "pi", 20000L, "CNY")).isEqualTo("refund_exists");
        verify(outbox, never()).append(anyString(), any(UUID.class), anyString(), anyString(), any(Map.class));
    }

    @Test
    void convertVoidToRefundReturnsBookingMissingWhenAbsent() {
        UUID bookingId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(bookingId))).thenReturn(List.of());
        assertThat(transitions.convertVoidToRefund(bookingId, "pi", 20000L, "CNY")).isEqualTo("booking_missing");
    }

    @Test
    void refundFailedRecordsManualReviewAndEmitsEvent() {
        UUID bookingId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        when(jdbc.query(contains("FROM refunds WHERE id = ?"), any(RowMapper.class), eq(refundId)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rowWith(bookingId, "PENDING"), 0));
                });
        transitions.refundFailed(refundId, true);
        verify(jdbc).update(contains("UPDATE refunds SET state = ?"), eq("MANUAL_REVIEW"), eq(refundId));
        verify(outbox).append(eq("booking"), eq(bookingId), anyString(), eq("refund.failed"), any(Map.class));
    }

    @Test
    void refundFailedWithoutManualReviewKeepsReservation() {
        UUID bookingId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        when(jdbc.query(contains("FROM refunds WHERE id = ?"), any(RowMapper.class), eq(refundId)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rowWith(bookingId, "PENDING"), 0));
                });
        transitions.refundFailed(refundId, false);
        verify(jdbc).update(contains("refund_state = ?"), any(Object[].class));
        verify(outbox, never()).append(anyString(), any(UUID.class), anyString(), eq("refund.failed"),
                any(Map.class));
    }

    @Test
    void refundFailedIsNoOpForAlreadySucceededRefund() {
        UUID bookingId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        when(jdbc.query(contains("FROM refunds WHERE id = ?"), any(RowMapper.class), eq(refundId)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rowWith(bookingId, "SUCCEEDED"), 0));
                });
        transitions.refundFailed(refundId, true);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void failCaptureReleasesStockWhenPaymentPending() {
        UUID bookingId = UUID.randomUUID();
        lockReturns(row(bookingId, "PAYMENT_PENDING", "NONE"));
        assertThat(transitions.failCapture(bookingId, "pi")).isEqualTo("failed");
        verify(outbox).append(eq("booking"), eq(bookingId), anyString(), eq("payment.failed"), any(Map.class));
    }

    @Test
    void failCaptureIsNoSideEffectWhenBookingAlreadyTerminal() {
        UUID bookingId = UUID.randomUUID();
        lockReturns(row(bookingId, "CONFIRMED", "NONE"));
        assertThat(transitions.failCapture(bookingId, "pi")).isEqualTo("no_side_effect");
        verify(outbox, never()).append(anyString(), any(UUID.class), anyString(), eq("payment.failed"),
                any(Map.class));
    }

    @Test
    void failCaptureReturnsBookingMissingWhenAbsent() {
        UUID bookingId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(bookingId))).thenReturn(List.of());
        assertThat(transitions.failCapture(bookingId, "pi")).isEqualTo("booking_missing");
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

    /** Builds a fake ResultSet answering the two columns the refund mappers read. */
    private java.sql.ResultSet rowWith(UUID bookingId, String state) throws java.sql.SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getObject("booking_id", UUID.class)).thenReturn(bookingId);
        when(rs.getString("state")).thenReturn(state);
        return rs;
    }
}
