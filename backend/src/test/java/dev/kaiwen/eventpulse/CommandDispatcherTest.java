package dev.kaiwen.eventpulse;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.service.BookingTransitions;
import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.DbClock;
import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.payment.CommandDispatcher;
import dev.kaiwen.eventpulse.payment.SimulatedPaymentGateway;
import dev.kaiwen.eventpulse.payment.SimulatedPaymentGateway.GatewayResult;
import dev.kaiwen.eventpulse.payment.SimulatedPaymentGateway.Outcome;
import dev.kaiwen.eventpulse.service.TicketIssuer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CommandDispatcher resolution matrix driven through tick(): capture
 * success/failure/UNKNOWN, void conversion, refund success/failure/UNKNOWN,
 * exception handling and manual retry.
 */
class CommandDispatcherTest {

    private JdbcTemplate jdbc;
    private BookingTransitions transitions;
    private SimulatedPaymentGateway gateway;
    private CommandDispatcher dispatcher;
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        transitions = mock(BookingTransitions.class);
        gateway = mock(SimulatedPaymentGateway.class);
        txManager = mock(PlatformTransactionManager.class);
        AppProperties properties = new AppProperties(
                new AppProperties.Security("s", "p", Duration.ofMinutes(1), Duration.ofDays(1),
                        Duration.ofMinutes(10), List.of(), Boolean.FALSE),
                null,
                new AppProperties.Commands(Duration.ofMillis(10), 10, Duration.ofSeconds(30), 8,
                        Duration.ofSeconds(1)),
                null, new AppProperties.Gateway("", Duration.ofSeconds(1)), null, null);
        dispatcher = new CommandDispatcher(jdbc, new TransactionTemplate(txManager), gateway, transitions,
                properties, "unit");
    }

    private CommandDispatcher.CommandRow row(String kind, UUID aggregateId, String providerKey,
            String targetKey, String state, int attempts) {
        return new CommandDispatcher.CommandRow(UUID.randomUUID(), kind, "booking", aggregateId,
                providerKey, targetKey, state, attempts, 8);
    }

    private void claimReturns(List<CommandDispatcher.CommandRow> rows) {
        when(jdbc.query(contains("FOR UPDATE SKIP LOCKED"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(rows);
        when(txManager.getTransaction(any())).thenReturn(
                new org.springframework.transaction.support.SimpleTransactionStatus());
    }

    private void stubAmount(long amount) {
        when(jdbc.queryForObject(contains("requested_amount_minor"), eq(Long.class), any(Object[].class)))
                .thenReturn(amount);
        when(jdbc.queryForObject(contains("requested_amount_minor"), eq(Long.class),
                any(UUID.class), anyString())).thenReturn(amount);
    }

    private void stubAttempts(int attempts) {
        when(jdbc.queryForObject(contains("SELECT attempts"), eq(Integer.class), any(Object[].class)))
                .thenReturn(attempts);
    }

    @Test
    void captureSuccessConfirmsAndCompletes() {
        UUID booking = UUID.randomUUID();
        claimReturns(List.of(row("CAPTURE", booking, "pi-1", null, "READY", 0)));
        stubAmount(20000);
        stubAttempts(1);
        when(gateway.capture(eq("pi-1"), eq(20000L))).thenReturn(new GatewayResult(Outcome.SUCCESS, "SUCCESS"));
        when(transitions.completeCapture(eq(booking), eq("pi-1"), eq(20000L), eq("CNY"), anyString()))
                .thenReturn("confirmed");

        dispatcher.tick();

        verify(transitions).completeCapture(eq(booking), eq("pi-1"), eq(20000L), eq("CNY"), anyString());
        verify(jdbc).update(contains("SET state = 'COMPLETED'"), any(Object[].class));
    }

    @Test
    void captureFailureFailsBooking() {
        UUID booking = UUID.randomUUID();
        claimReturns(List.of(row("CAPTURE", booking, "pi-2", null, "READY", 0)));
        stubAmount(20000);
        stubAttempts(1);
        when(gateway.capture(eq("pi-2"), eq(20000L))).thenReturn(new GatewayResult(Outcome.FAILURE, "FAILURE"));
        when(transitions.failCapture(booking, "pi-2")).thenReturn("failed");

        dispatcher.tick();

        verify(transitions).failCapture(booking, "pi-2");
        verify(jdbc).update(contains("SET state = 'COMPLETED'"), any(Object[].class));
    }

    @Test
    void captureUnknownEntersStatusQueryLoop() {
        UUID booking = UUID.randomUUID();
        claimReturns(List.of(row("CAPTURE", booking, "pi-3", null, "READY", 0)));
        stubAmount(20000);
        stubAttempts(1);
        when(gateway.capture(eq("pi-3"), eq(20000L))).thenReturn(new GatewayResult(Outcome.UNKNOWN,
                "LATE_SUCCESS"));

        dispatcher.tick();

        verify(jdbc).update(contains("UNKNOWN_QUERY"), any(Object[].class));
        verify(jdbc, never()).update(contains("SET state = 'COMPLETED'"), any(Object[].class));
    }

    @Test
    void voidOnCapturedChargeConvertsToRefund() {
        UUID booking = UUID.randomUUID();
        claimReturns(List.of(row("VOID", booking, "vd-pi-4", "pi-4", "READY", 0)));
        stubAmount(20000);
        stubAttempts(1);
        when(gateway.voidCharge(eq("vd-pi-4"), eq("pi-4"))).thenReturn(new GatewayResult(Outcome.UNKNOWN,
                "ALREADY_CAPTURED"));
        when(transitions.convertVoidToRefund(booking, "pi-4", 20000L, "CNY")).thenReturn("refund_created");

        dispatcher.tick();

        verify(transitions).convertVoidToRefund(booking, "pi-4", 20000L, "CNY");
        verify(transitions, never()).completeVoid(any(), anyString(), anyString(), anyString());
    }

    @Test
    void refundFailureExhaustedGoesToManualReview() {
        UUID booking = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        claimReturns(List.of(new CommandDispatcher.CommandRow(commandId, "REFUND", "booking", booking,
                "rf-1", "pi-1", "READY", 8, 8)));
        stubAttempts(8);
        when(jdbc.query(contains("FROM refunds WHERE command_id = ?"), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of(new CommandDispatcher.Ref(10000L, refundId)));
        when(gateway.refund(eq("rf-1"), eq("pi-1"), eq(10000L))).thenReturn(new GatewayResult(Outcome.FAILURE,
                "FAILURE"));

        dispatcher.tick();

        verify(transitions).refundFailed(refundId, true);
        verify(jdbc).update(contains("MANUAL_REVIEW"), any(Object[].class));
    }

    @Test
    void refundSuccessCallsRefundSucceeded() {
        UUID booking = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        claimReturns(List.of(new CommandDispatcher.CommandRow(UUID.randomUUID(), "REFUND", "booking", booking,
                "rf-2", "pi-2", "READY", 1, 8)));
        stubAttempts(1);
        when(jdbc.query(contains("FROM refunds WHERE command_id = ?"), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of(new CommandDispatcher.Ref(10000L, refundId)));
        when(gateway.refund(eq("rf-2"), eq("pi-2"), eq(10000L))).thenReturn(new GatewayResult(Outcome.SUCCESS,
                "SUCCESS"));

        dispatcher.tick();

        verify(transitions).refundSucceeded(refundId, 10000L, "rf-2");
    }

    @Test
    void tickNeverReplaysTheActionForAnUnknownCommand() {
        UUID booking = UUID.randomUUID();
        claimReturns(List.of(row("CAPTURE", booking, "pi-unknown", null, "UNKNOWN_QUERY", 1)));
        stubAttempts(1);
        stubAmount(20000);
        when(gateway.queryStatus("pi-unknown")).thenReturn(new GatewayResult(Outcome.SUCCESS, "query"));
        when(transitions.completeCapture(eq(booking), eq("pi-unknown"), eq(20000L), eq("CNY"), anyString()))
                .thenReturn("confirmed");

        dispatcher.tick();

        verify(gateway).queryStatus("pi-unknown");
        verify(gateway, never()).capture(anyString(), any(Long.class));
    }

    @Test
    void unknownQueryResolutionHonoursGatewayStatus() {
        UUID booking = UUID.randomUUID();
        // resolveUnknownTick claims UNKNOWN_QUERY commands
        when(jdbc.query(contains("state = 'UNKNOWN_QUERY'"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(row("CAPTURE", booking, "pi-5", null, "UNKNOWN_QUERY", 1)));
        stubAttempts(1);
        stubAmount(20000);
        when(gateway.queryStatus("pi-5")).thenReturn(new GatewayResult(Outcome.SUCCESS, "query"));
        when(transitions.completeCapture(eq(booking), eq("pi-5"), eq(20000L), eq("CNY"), anyString()))
                .thenReturn("confirmed");

        dispatcher.resolveUnknownTick();

        verify(transitions).completeCapture(eq(booking), eq("pi-5"), eq(20000L), eq("CNY"), anyString());
    }

    @Test
    void unknownQueryExhaustedForRefundGoesToManualReview() {
        UUID booking = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        when(jdbc.query(contains("state = 'UNKNOWN_QUERY'"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new CommandDispatcher.CommandRow(UUID.randomUUID(), "REFUND", "booking",
                        booking, "rf-3", "pi-3", "UNKNOWN_QUERY", 8, 8)));
        stubAttempts(8);
        when(jdbc.query(contains("FROM refunds WHERE command_id = ?"), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of(new CommandDispatcher.Ref(10000L, refundId)));
        when(gateway.queryStatus("rf-3")).thenReturn(new GatewayResult(Outcome.UNKNOWN, "query"));

        dispatcher.resolveUnknownTick();

        verify(transitions).refundFailed(refundId, true);
    }

    @Test
    void processingExceptionRetriesWithBackoffInsteadOfLosingCommand() {
        UUID booking = UUID.randomUUID();
        claimReturns(List.of(row("CAPTURE", booking, "pi-6", null, "READY", 0)));
        stubAttempts(1);
        stubAmount(20000);
        when(gateway.capture(eq("pi-6"), eq(20000L))).thenThrow(new IllegalStateException("gateway down"));

        dispatcher.tick();

        verify(jdbc).update(contains("SET state = 'READY'"), any(Object[].class));
    }

    @Test
    void manualRetryRequeuesManualReviewCommands() {
        dispatcher.manualRetry(UUID.randomUUID(), "admin", "it");
        verify(jdbc).update(contains("state = 'READY'"), any(Object[].class));
    }

    @Test
    void emptyClaimBatchIsANoOp() {
        claimReturns(List.of());
        dispatcher.tick();
        verify(jdbc, never()).update(contains("SET state = 'COMPLETED'"), any(Object[].class));
    }
}
