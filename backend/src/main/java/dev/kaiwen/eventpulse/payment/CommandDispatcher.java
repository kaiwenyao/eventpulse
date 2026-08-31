package dev.kaiwen.eventpulse.payment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.service.BookingTransitions;
import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.payment.SimulatedPaymentGateway.GatewayResult;
import dev.kaiwen.eventpulse.payment.SimulatedPaymentGateway.Outcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable-command dispatcher. Business transactions only insert commands;
 * this worker claims READY (or lease-expired) commands under a short lease,
 * performs the gateway call OUTSIDE any transaction, then resolves the
 * outcome in a fresh transaction (attempt record + state + outbox). UNKNOWN
 * results enter a status-query loop and never guess the outcome. Exhausted
 * commands land in MANUAL_REVIEW; manual retries reuse the original
 * providerKey.
 */
@Component
public class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    public record CommandRow(UUID id, String kind, String aggregateType, UUID aggregateId, String providerKey,
                             String targetProviderKey, String state, int attempts, int maxAttempts, String leaseMode) {
        /** Compatibility constructor for callers that only need the durable command fields. */
        public CommandRow(UUID id, String kind, String aggregateType, UUID aggregateId, String providerKey,
                          String targetProviderKey, String state, int attempts, int maxAttempts) {
            this(id, kind, aggregateType, aggregateId, providerKey, targetProviderKey, state, attempts,
                    maxAttempts, null);
        }
    }

    private static final String EXECUTE_LEASE = "EXECUTE";
    private static final String QUERY_LEASE = "QUERY";

    // Background batch work runs on the dedicated batch pool (plan §3.1), so
    // dispatcher bookkeeping can never starve the transactional write pool.
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SimulatedPaymentGateway gateway;
    private final BookingTransitions transitions;
    private final AppProperties properties;
    private final String instanceId;

    public CommandDispatcher(@Qualifier("batchJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("batchTransactionTemplate") TransactionTemplate tx,
            SimulatedPaymentGateway gateway, BookingTransitions transitions, AppProperties properties,
            @Value("${eventpulse.instance-id:}") String configuredInstanceId) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.gateway = gateway;
        this.transitions = transitions;
        this.properties = properties;
        this.instanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? "dispatch-" + UUID.randomUUID().toString().substring(0, 8)
                : configuredInstanceId;
    }

    @Scheduled(fixedDelayString = "${eventpulse.commands.dispatcher-interval:PT0.5S}")
    public void tick() {
        List<CommandRow> batch = claim();
        for (CommandRow command : batch) {
            try {
                process(command);
            }
            catch (Exception e) {
                log.error("command {} processing error", command.id(), e);
                markException(command, e.getMessage());
            }
        }
    }

    /** Claims only new/retried external actions. UNKNOWN_QUERY is deliberately
     * absent: an unknown result may never be sent to the original operation. */
    private List<CommandRow> claim() {
        return claim("""
                WHERE (state = 'READY' AND next_attempt_at <= now())
                   OR (state = 'RUNNING' AND lease_mode = 'EXECUTE' AND lease_until < now())
                """, EXECUTE_LEASE);
    }

    /** Claims status queries under the same lease protocol as action calls.
     * The short transaction closes the old FOR UPDATE/SKIP LOCKED window: the
     * row is leased before queryStatus runs outside the transaction. */
    private List<CommandRow> claimUnknown() {
        return claim("""
                WHERE (state = 'UNKNOWN_QUERY' AND next_attempt_at <= now())
                   OR (state = 'RUNNING' AND lease_mode = 'QUERY' AND lease_until < now())
                """, QUERY_LEASE);
    }

    private List<CommandRow> claim(String predicate, String leaseMode) {
        return tx.execute(status -> {
            List<CommandRow> rows = jdbc.query("""
                    SELECT id, kind, aggregate_type, aggregate_id, provider_key, target_provider_key, state,
                           attempts, max_attempts, lease_mode
                    FROM commands
                    """ + predicate + """
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, (rs, i) -> new CommandRow(rs.getObject("id", UUID.class), rs.getString("kind"),
                    rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                    rs.getString("provider_key"), rs.getString("target_provider_key"), rs.getString("state"),
                    rs.getInt("attempts"), rs.getInt("max_attempts"), rs.getString("lease_mode")),
                    properties.commands().batchSize());
            for (CommandRow row : rows) {
                jdbc.update("""
                        UPDATE commands SET state = 'RUNNING', lease_mode = ?, lease_owner = ?,
                               lease_acquired_at = now(), lease_until = now() + make_interval(secs => ?),
                               attempts = attempts + 1, updated_at = now()
                        WHERE id = ?
                        """, leaseMode, instanceId, properties.commands().lease().toSeconds(), row.id());
            }
            return rows;
        });
    }

    private void process(CommandRow command) {
        // State is authoritative. An UNKNOWN command is never dispatched as
        // its original action; a reclaimed QUERY lease is also routed here.
        if ("UNKNOWN_QUERY".equals(command.state()) || QUERY_LEASE.equals(command.leaseMode())) {
            resolveUnknown(command);
            return;
        }
        switch (command.kind()) {
            case "CAPTURE" -> {
                long amount = intentAmount(command.aggregateId(), command.providerKey());
                GatewayResult result = gateway.capture(command.providerKey(), amount);
                resolveCapture(command, result, amount);
            }
            case "VOID" -> {
                GatewayResult result = gateway.voidCharge(command.providerKey(), command.targetProviderKey());
                resolveVoid(command, result);
            }
            case "REFUND" -> {
                List<Ref> refs = lookupRefund(command.id());
                long amount = refs.isEmpty() ? 0 : refs.getFirst().amountMinor();
                UUID refundId = refs.isEmpty() ? null : refs.getFirst().refundId();
                GatewayResult result = gateway.refund(command.providerKey(), command.targetProviderKey(), amount);
                resolveRefund(command, result, amount, refundId);
            }
            default -> complete(command, Map.of("skipped", "unknown kind"));
        }
    }

    private void resolveCapture(CommandRow command, GatewayResult result, long amount) {
        log.info("[dispatcher] capture command {} outcome={} amount={}", command.id(), result.outcome(), amount);
        switch (result.outcome()) {
            case SUCCESS -> {
                String effect = transitions.completeCapture(command.aggregateId(), command.providerKey(), amount,
                        "CNY", "gw-" + command.providerKey());
                complete(command, Map.of("outcome", "SUCCESS", "effect", effect));
            }
            case FAILURE -> {
                String effect = transitions.failCapture(command.aggregateId(), command.providerKey());
                complete(command, Map.of("outcome", "FAILURE", "effect", effect));
            }
            case UNKNOWN -> enterUnknownQuery(command);
        }
    }

    private void resolveVoid(CommandRow command, GatewayResult result) {
        if (result.outcome() == Outcome.UNKNOWN && "ALREADY_CAPTURED".equals(result.detail())) {
            long amount = intentAmount(command.aggregateId(), command.targetProviderKey());
            String effect = transitions.convertVoidToRefund(command.aggregateId(), command.targetProviderKey(),
                    amount, "CNY");
            complete(command, Map.of("outcome", "VOID_CONVERTED_TO_REFUND", "effect", effect));
            return;
        }
        switch (result.outcome()) {
            case SUCCESS -> {
                String effect = transitions.completeVoid(command.aggregateId(), command.providerKey(),
                        command.targetProviderKey(), result.detail());
                complete(command, Map.of("outcome", "VOIDED", "effect", effect));
            }
            case UNKNOWN -> enterUnknownQuery(command);
            case FAILURE -> retryWithBackoff(command, "void failed");
        }
    }

    private void resolveRefund(CommandRow command, GatewayResult result, long amount, UUID refundId) {
        log.info("[dispatcher] refund command {} outcome={} amount={} refundId={}", command.id(),
                result.outcome(), amount, refundId);
        switch (result.outcome()) {
            case SUCCESS -> {
                if (refundId != null) {
                    transitions.refundSucceeded(refundId, amount, command.providerKey());
                }
                complete(command, Map.of("outcome", "SUCCESS"));
            }
            case FAILURE -> {
                boolean exhausted = attemptsOf(command.id()) >= command.maxAttempts();
                if (refundId != null) {
                    transitions.refundFailed(refundId, exhausted);
                }
                if (exhausted) {
                    toManualReview(command, "refund failed after max attempts");
                }
                else {
                    retryWithBackoff(command, "refund failed, kept reservation");
                }
            }
            case UNKNOWN -> enterUnknownQuery(command);
        }
    }

    /** UNKNOWN never guesses: it polls the gateway status until resolved. */
    private void enterUnknownQuery(CommandRow command) {
        jdbc.update("""
                UPDATE commands SET state = 'UNKNOWN_QUERY', lease_mode = 'QUERY', lease_owner = NULL,
                       lease_acquired_at = NULL, lease_until = NULL,
                       next_attempt_at = now() + make_interval(secs => ?), updated_at = now()
                WHERE id = ? AND lease_owner = ?
                """, properties.commands().unknownResolveInterval().toSeconds(), command.id(), instanceId);
        recordAttempt(command.id(), attemptsOf(command.id()), "UNKNOWN", Map.of());
    }

    private void resolveUnknown(CommandRow command) {
        String keyToQuery = switch (command.kind()) {
            case "CAPTURE" -> command.providerKey();
            case "REFUND" -> command.providerKey();
            case "VOID" -> command.targetProviderKey();
            default -> command.providerKey();
        };
        GatewayResult status = gateway.queryStatus(keyToQuery);
        boolean exhausted = attemptsOf(command.id()) >= command.maxAttempts();
        switch (command.kind()) {
            case "CAPTURE" -> {
                if (status.outcome() == Outcome.UNKNOWN) {
                    if (exhausted) {
                        toManualReview(command, "capture stuck UNKNOWN");
                    }
                    else {
                        enterUnknownQuery(command);
                    }
                    return;
                }
                resolveCapture(command, status, intentAmount(command.aggregateId(),
                        command.providerKey()));
            }
            case "VOID" -> {
                if (status.outcome() == Outcome.UNKNOWN) {
                    GatewayResult own = gateway.queryStatus(command.providerKey());
                    if (own.outcome() == Outcome.SUCCESS) {
                        resolveVoid(command, new GatewayResult(Outcome.SUCCESS, "query"));
                        return;
                    }
                    if (exhausted) {
                        toManualReview(command, "void stuck UNKNOWN");
                    }
                    else {
                        retryWithBackoff(command, "void UNKNOWN");
                    }
                    return;
                }
                resolveVoid(command, status);
            }
            case "REFUND" -> {
                List<Ref> refs = lookupRefund(command.id());
                long amount = refs.isEmpty() ? 0 : refs.getFirst().amountMinor();
                UUID refundId = refs.isEmpty() ? null : refs.getFirst().refundId();
                if (status.outcome() == Outcome.UNKNOWN) {
                    if (exhausted) {
                        if (refundId != null) {
                            transitions.refundFailed(refundId, true);
                        }
                        toManualReview(command, "refund stuck UNKNOWN");
                    }
                    else {
                        retryWithBackoff(command, "refund UNKNOWN");
                    }
                    return;
                }
                resolveRefund(command, status, amount, refundId);
            }
            default -> complete(command, Map.of());
        }
    }

    private void complete(CommandRow command, Map<String, Object> result) {
        log.info("[dispatcher] command {} {} COMPLETED {}", command.id(), command.kind(), result);
        jdbc.update("""
                UPDATE commands SET state = 'COMPLETED', result = ?::jsonb, lease_owner = NULL,
                       lease_acquired_at = NULL, lease_until = NULL, last_error = NULL,
                       completed_at = now(), updated_at = now()
                WHERE id = ? AND lease_owner = ?
                """, dev.kaiwen.eventpulse.outbox.OutboxJson.write(result), command.id(), instanceId);
        recordAttempt(command.id(), attemptsOf(command.id()), "SUCCESS", result);
    }

    private void retryWithBackoff(CommandRow command, String error) {
        long backoffSeconds = (long) Math.pow(2, Math.min(attemptsOf(command.id()), 6));
        jdbc.update("""
                UPDATE commands SET state = 'READY', lease_owner = NULL, lease_acquired_at = NULL,
                       lease_until = NULL, lease_mode = 'EXECUTE',
                       next_attempt_at = now() + make_interval(secs => ?), last_error = ?, updated_at = now()
                WHERE id = ? AND lease_owner = ?
                """, backoffSeconds, error, command.id(), instanceId);
        recordAttempt(command.id(), attemptsOf(command.id()), "FAILURE", Map.of("error", error));
    }

    private void toManualReview(CommandRow command, String reason) {
        jdbc.update("""
                UPDATE commands SET state = 'MANUAL_REVIEW', lease_owner = NULL, lease_acquired_at = NULL,
                       lease_until = NULL, last_error = ?, updated_at = now() WHERE id = ? AND lease_owner = ?
                """, reason, command.id(), instanceId);
        recordAttempt(command.id(), attemptsOf(command.id()), "FAILURE", Map.of("manualReview", reason));
    }

    private void markException(CommandRow command, String error) {
        tx.executeWithoutResult(status -> {
            boolean exhausted = attemptsOf(command.id()) >= command.maxAttempts();
            if (exhausted) {
                toManualReview(command, error);
            }
            else {
                retryWithBackoff(command, error);
            }
        });
    }

    private void recordAttempt(UUID commandId, int attemptNo, String outcome, Map<String, Object> detail) {
        jdbc.update("""
                INSERT INTO command_attempts (command_id, attempt_no, outcome, detail)
                VALUES (?, ?, ?, ?::jsonb)
                """, commandId, attemptNo, outcome, dev.kaiwen.eventpulse.outbox.OutboxJson.write(detail));
    }

    private int attemptsOf(UUID commandId) {
        Integer attempts = jdbc.queryForObject("SELECT attempts FROM commands WHERE id = ?", Integer.class,
                commandId);
        return attempts == null ? 0 : attempts;
    }

    private long intentAmount(UUID bookingId, String captureProviderKey) {
        Long amount = jdbc.queryForObject("""
                SELECT requested_amount_minor FROM payment_intents
                WHERE booking_id = ? AND provider_key = ?
                """, Long.class, bookingId, captureProviderKey);
        return amount == null ? 0L : amount;
    }

    /** The refund a REFUND command settles, found via its owning command id. */
    public record Ref(long amountMinor, UUID refundId) {
    }

    private List<Ref> lookupRefund(UUID commandId) {
        return jdbc.query("SELECT amount_minor, id FROM refunds WHERE command_id = ?",
                (rs, i) -> new Ref(rs.getLong("amount_minor"), rs.getObject("id", UUID.class)), commandId);
    }

    /** Used by the UNKNOWN resolution tick, scheduled separately at lower frequency. */
    @Scheduled(fixedDelayString = "${eventpulse.gateway.unknown-resolve-interval:PT5S}")
    public void resolveUnknownTick() {
        List<CommandRow> batch = claimUnknown();
        for (CommandRow command : batch) {
            try {
                resolveUnknown(command);
            }
            catch (Exception e) {
                log.error("unknown-resolve error for command {}", command.id(), e);
            }
        }
    }

    /** Manual retry from the admin queue: reuses the original providerKey. */
    public void manualRetry(UUID commandId, String adminActor, String reason) {
        tx.executeWithoutResult(status -> jdbc.update("""
                UPDATE commands SET state = 'READY', lease_mode = 'EXECUTE', next_attempt_at = now(),
                       last_error = NULL, lease_owner = NULL, lease_acquired_at = NULL,
                       lease_until = NULL, updated_at = now()
                WHERE id = ? AND state = 'MANUAL_REVIEW'
                """, commandId));
    }
}
