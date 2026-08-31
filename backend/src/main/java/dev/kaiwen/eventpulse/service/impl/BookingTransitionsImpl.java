package dev.kaiwen.eventpulse.service.impl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.common.DbClock;
import dev.kaiwen.eventpulse.dto.BookingDtos;
import dev.kaiwen.eventpulse.dto.BookingDtos.IntentRow;
import dev.kaiwen.eventpulse.dto.BookingDtos.PaymentIntentView;
import dev.kaiwen.eventpulse.exception.ApiException;
import dev.kaiwen.eventpulse.exception.ErrorCode;
import dev.kaiwen.eventpulse.outbox.OutboxJson;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.service.BookingTransitions;
import dev.kaiwen.eventpulse.service.TicketIssuer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Protocol-B state transitions. Every entry point locks rows in the fixed
 * order booking → quota → inventory → reservation → tickets → payment rows,
 * then re-validates status. Exactly one racing transition wins; losers return
 * without side effects. No external gateway call ever happens inside these
 * transactions - the dispatcher owns that boundary.
 */
@Service
public class BookingTransitionsImpl implements BookingTransitions {

    private static final Logger log = LoggerFactory.getLogger(BookingTransitionsImpl.class);

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final DbClock clock;
    private final TicketIssuer ticketIssuer;
    private final org.springframework.context.ApplicationEventPublisher events;

    public BookingTransitionsImpl(JdbcTemplate jdbc, OutboxWriter outbox, DbClock clock, TicketIssuer ticketIssuer,
            org.springframework.context.ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
        this.ticketIssuer = ticketIssuer;
        this.events = events;
    }

    private void publishStatus(UUID bookingId, String status, String refundState) {
        events.publishEvent(new BookingStatusChanged(bookingId, status, refundState));
    }

    private static final String LOCK_BOOKING = """
            SELECT b.id, b.user_id, b.event_id, b.tier_id, b.quantity, b.status, b.entitlement_status,
                   b.refund_state, b.unit_price_minor, b.currency, b.policy_snapshot::text AS policy_snapshot,
                   b.active_payment_intent_id, b.expires_at, b.version
            FROM bookings b WHERE b.id = ? FOR UPDATE
            """;

    private static final org.springframework.jdbc.core.RowMapper<BookingRow> MAP_BOOKING = (rs, i) ->
            new BookingRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                    rs.getObject("event_id", UUID.class), rs.getObject("tier_id", UUID.class),
                    rs.getInt("quantity"), rs.getString("status"), rs.getString("entitlement_status"),
                    rs.getString("refund_state"), rs.getObject("unit_price_minor", Long.class),
                    rs.getString("currency"), rs.getString("policy_snapshot"),
                    rs.getObject("active_payment_intent_id", UUID.class),
                    rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getInt("version"));

    // ---------------------------------------------------------- payment intent

    /**
     * Single-flight payment intent: only one active intent per booking via a
     * partial unique index; a second request (even with a different idempotency
     * key) receives the existing intent.
     */
    @Override
    @Transactional
    public PaymentIntentView createPaymentIntent(UUID bookingId) {
        BookingRow booking = jdbc.query(LOCK_BOOKING, MAP_BOOKING, bookingId).stream().findFirst()
                .orElseThrow(ApiException::notFound);
        if (!"PAYMENT_PENDING".equals(booking.status())) {
            throw new ApiException(ErrorCode.BOOKING_NOT_PAYABLE, "booking is not awaiting payment");
        }
        if (booking.expiresAt() != null && !clock.now().isBefore(booking.expiresAt())) {
            throw new ApiException(ErrorCode.BOOKING_NOT_PAYABLE, "booking has expired");
        }
        IntentRow existing = jdbc.query("""
                SELECT id, attempt_no, state, requested_amount_minor, captured_amount_minor, currency,
                       provider_key, active FROM payment_intents WHERE booking_id = ? AND active = TRUE
                """, (rs, i) -> new IntentRow(rs.getObject("id", UUID.class), rs.getInt("attempt_no"),
                rs.getString("state"), rs.getObject("requested_amount_minor", Long.class),
                rs.getObject("captured_amount_minor", Long.class), rs.getString("currency"),
                rs.getString("provider_key"), rs.getBoolean("active")), bookingId).stream().findFirst()
                .orElse(null);
        if (existing != null) {
            return new PaymentIntentView(existing.id(), existing.attemptNo(), existing.state(),
                    existing.requestedAmountMinor(), existing.capturedAmountMinor(), existing.currency(),
                    existing.providerKey(), existing.active());
        }
        Integer attemptNo = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM payment_intents WHERE booking_id = ?",
                Integer.class, bookingId);
        String providerKey = "pi-" + CanonicalJson.newOpaqueToken();
        UUID intentId = jdbc.queryForObject("""
                INSERT INTO payment_intents (booking_id, attempt_no, state, requested_amount_minor, currency,
                                             provider_key, active)
                VALUES (?, ?, 'CREATED', ?, ?, ?, TRUE)
                RETURNING id
                """, UUID.class, bookingId, attemptNo, totalMinor(booking), booking.currency(), providerKey);
        jdbc.update("""
                INSERT INTO payment_balance (booking_id, currency) VALUES (?, ?)
                ON CONFLICT (booking_id) DO NOTHING
                """, bookingId, booking.currency());
        jdbc.update("UPDATE bookings SET active_payment_intent_id = ?, updated_at = now() WHERE id = ?",
                intentId, bookingId);
        jdbc.update("""
                INSERT INTO commands (kind, aggregate_type, aggregate_id, provider_key)
                VALUES ('CAPTURE', 'booking', ?, ?)
                """, bookingId, providerKey);
        outbox.append("booking", bookingId, OutboxWriter.TOPIC_BOOKING, "payment.intent_created", Map.of(
                "bookingId", bookingId.toString(), "userId", booking.userId().toString(),
                "intentId", intentId.toString(), "providerKey", providerKey,
                "amountMinor", totalMinor(booking)));
        return new PaymentIntentView(intentId, attemptNo, "CREATED", totalMinor(booking), 0L,
                booking.currency(), providerKey, true);
    }

    private long totalMinor(BookingRow booking) {
        return booking.unitPriceMinor() * booking.quantity();
    }

    // ------------------------------------------------------------------ cancel

    @Override
    @Transactional
    public boolean cancel(UUID actorId, UUID bookingId, boolean eventCancelled, String actorKind) {
        BookingRow booking = jdbc.query(LOCK_BOOKING, MAP_BOOKING, bookingId).stream().findFirst()
                .orElseThrow(ApiException::notFound);
        switch (booking.status()) {
            case "PAYMENT_PENDING" -> cancelBeforePayment(booking);
            case "CONFIRMED" -> cancelConfirmed(booking, eventCancelled);
            case "CANCELLATION_PENDING", "CANCELLED", "CANCELLED_BEFORE_PAYMENT", "EXPIRED" -> {
                // Idempotent re-run: nothing left to do.
                return false;
            }
            default -> throw new ApiException(ErrorCode.BOOKING_NOT_CANCELLABLE,
                    "booking cannot be cancelled in state " + booking.status());
        }
        return true;
    }

    private void cancelBeforePayment(BookingRow booking) {
        int q = booking.quantity();
        // Release stock and quota (guarded conditional updates keep the
        // same-row inventory invariant intact even if races reset rows).
        jdbc.update("""
                UPDATE inventory SET reserved = reserved - ?, available = available + ?, version = version + 1
                WHERE tier_id = ? AND reserved >= ?
                """, q, q, booking.tierId(), q);
        jdbc.update("""
                UPDATE user_tier_quota SET active_quantity = active_quantity - ?, version = version + 1
                WHERE user_id = ? AND tier_id = ? AND active_quantity >= ?
                """, q, booking.userId(), booking.tierId(), q);
        jdbc.update("UPDATE reservations SET status = 'RELEASED', updated_at = now() WHERE booking_id = ?",
                booking.id());
        jdbc.update("""
                UPDATE bookings SET status = 'CANCELLED_BEFORE_PAYMENT', entitlement_status = 'REVOKED',
                       cancelled_at = now(), active_payment_intent_id = NULL, updated_at = now()
                WHERE id = ?
                """, booking.id());
        IntentRow active = activeIntent(booking.id());
        if (active != null) {
            jdbc.update("UPDATE payment_intents SET state = 'CANCELED', active = FALSE, updated_at = now() "
                    + "WHERE id = ?", active.id());
            // A capture command not yet claimed by the dispatcher is cancelled;
            // one already RUNNING is handled by late-capture compensation.
            int cancelledCommands = jdbc.update("""
                    UPDATE commands SET state = 'CANCELED', updated_at = now()
                    WHERE provider_key = ? AND state IN ('READY', 'UNKNOWN_QUERY')
                    """, active.providerKey());
            if (isCaptureRunning(active.providerKey())) {
                createVoidCommand(booking.id(), active.providerKey());
            }
        }
        outbox.append("booking", booking.id(), OutboxWriter.TOPIC_BOOKING, "booking.cancelled", Map.of(
                "bookingId", booking.id().toString(), "userId", booking.userId().toString(), "phase",
                "before_payment"));
        publishStatus(booking.id(), "CANCELLED_BEFORE_PAYMENT", "NONE");
    }

    private void cancelConfirmed(BookingRow booking, boolean eventCancelled) {
        Map<String, Object> policy = OutboxJson.mapper().readValue(booking.policySnapshot(), Map.class);
        boolean cancellable = Boolean.TRUE.equals(policy.get("cancellable")) || eventCancelled;
        if (!cancellable) {
            throw new ApiException(ErrorCode.BOOKING_NOT_CANCELLABLE, "policy snapshot forbids cancellation");
        }
        int deadlineHours = policy.get("cancellationDeadlineHoursBeforeStart") instanceof Number n
                ? n.intValue() : 0;
        Instant startsAt = jdbc.queryForObject("SELECT starts_at FROM events WHERE id = ?",
                java.time.OffsetDateTime.class, booking.eventId()).toInstant();
        if (!eventCancelled && deadlineHours > 0 && startsAt != null) {
            Instant deadline = startsAt.minus(java.time.Duration.ofHours(deadlineHours));
            if (!clock.now().isBefore(deadline)) {
                throw new ApiException(ErrorCode.BOOKING_NOT_CANCELLABLE,
                        "cancellation deadline passed for this policy version");
            }
        }
        // Any USED ticket refuses the whole-order auto cancellation (manual review).
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE booking_id = ? AND status = 'USED'",
                Integer.class, booking.id());
        if (used != null && used > 0) {
            outbox.append("booking", booking.id(), OutboxWriter.TOPIC_BOOKING,
                    "booking.cancellation_rejected_used_ticket", Map.of("bookingId", booking.id().toString(),
                            "userId", booking.userId().toString(), "usedTickets", used));
            throw new ApiException(ErrorCode.TICKET_ALREADY_USED,
                    "used tickets exist; order cancellation requires manual handling");
        }
        int q = booking.quantity();
        boolean resaleAllowed = Boolean.TRUE.equals(policy.get("resaleAllowed"));
        // Revoke entitlement first, then money moves.
        jdbc.update("UPDATE tickets SET status = 'REVOKED' WHERE booking_id = ? AND status = 'ACTIVE'",
                booking.id());
        jdbc.update("""
                UPDATE bookings SET status = 'CANCELLATION_PENDING', entitlement_status = 'REVOKED',
                       refund_state = 'PENDING', updated_at = now()
                WHERE id = ?
                """, booking.id());
        jdbc.update("""
                UPDATE reservations SET status = ?, updated_at = now() WHERE booking_id = ?
                """, resaleAllowed ? "RELEASED" : "WITHHELD", booking.id());
        if (resaleAllowed) {
            jdbc.update("""
                    UPDATE inventory SET sold = sold - ?, available = available + ?, version = version + 1
                    WHERE tier_id = ? AND sold >= ?
                    """, q, q, booking.tierId(), q);
        }
        else {
            jdbc.update("""
                    UPDATE inventory SET sold = sold - ?, withheld = withheld + ?, version = version + 1
                    WHERE tier_id = ? AND sold >= ?
                    """, q, q, booking.tierId(), q);
        }
        jdbc.update("""
                UPDATE user_tier_quota SET confirmed_quantity = confirmed_quantity - ?, version = version + 1
                WHERE user_id = ? AND tier_id = ? AND confirmed_quantity >= ?
                """, q, booking.userId(), booking.tierId(), q);

        // Reserve the refund amount atomically on the single balance row BEFORE
        // creating the external refund command.
        jdbc.update("""
                INSERT INTO payment_balance (booking_id, currency) VALUES (?, ?)
                ON CONFLICT (booking_id) DO NOTHING
                """, booking.id(), booking.currency());
        long refundable = jdbc.queryForObject("""
                SELECT captured_amount_minor - refund_reserved_amount_minor - refunded_amount_minor
                FROM payment_balance WHERE booking_id = ?
                """, Long.class, booking.id());
        if (refundable <= 0) {
            // Nothing captured: cancellation completes without a refund.
            jdbc.update("""
                    UPDATE bookings SET status = 'CANCELLED', refund_state = 'NONE', cancelled_at = now(),
                           updated_at = now() WHERE id = ?
                    """, booking.id());
            outbox.append("booking", booking.id(), OutboxWriter.TOPIC_BOOKING, "booking.cancelled", Map.of(
                    "bookingId", booking.id().toString(), "userId", booking.userId().toString(), "phase",
                    "no_refund"));
            publishStatus(booking.id(), "CANCELLED", "NONE");
            return;
        }
        int reserved = jdbc.update("""
                UPDATE payment_balance SET refund_reserved_amount_minor = refund_reserved_amount_minor + ?,
                       version = version + 1, updated_at = now()
                WHERE booking_id = ? AND refund_reserved_amount_minor + refunded_amount_minor + ? <= captured_amount_minor
                """, refundable, booking.id(), refundable);
        if (reserved == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "refund reservation invariant violated");
        }
        IntentRow intent = activeIntent(booking.id());
        UUID intentId = intent != null ? intent.id() : jdbc.queryForObject(
                "SELECT id FROM payment_intents WHERE booking_id = ? ORDER BY attempt_no DESC LIMIT 1",
                UUID.class, booking.id());
        String refundProviderKey = "rf-" + CanonicalJson.newOpaqueToken();
        UUID commandId = jdbc.queryForObject("""
                INSERT INTO commands (kind, aggregate_type, aggregate_id, provider_key, target_provider_key)
                VALUES ('REFUND', 'booking', ?, ?, ?)
                RETURNING id
                """, UUID.class, booking.id(), refundProviderKey, intent != null ? intent.providerKey() : null);
        UUID refundId = jdbc.queryForObject("""
                INSERT INTO refunds (payment_id, booking_id, amount_minor, state, command_id)
                VALUES (?, ?, ?, 'PENDING', ?)
                RETURNING id
                """, UUID.class, intentId, booking.id(), refundable, commandId);
        outbox.append("booking", booking.id(), OutboxWriter.TOPIC_BOOKING, "refund.requested", Map.of(
                "bookingId", booking.id().toString(), "userId", booking.userId().toString(), "refundId",
                refundId.toString(), "amountMinor", refundable));
    }

    // ------------------------------------------------------------------ expire

    @Override
    @Transactional
    public boolean expireBooking(UUID bookingId) {
        BookingRow booking = jdbc.query(LOCK_BOOKING, MAP_BOOKING, bookingId).stream().findFirst().orElse(null);
        if (booking == null || !"PAYMENT_PENDING".equals(booking.status())) {
            return false; // a racing payment/cancel won
        }
        if (booking.expiresAt() == null || clock.now().isBefore(booking.expiresAt())) {
            return false;
        }
        int q = booking.quantity();
        jdbc.update("""
                UPDATE inventory SET reserved = reserved - ?, available = available + ?, version = version + 1
                WHERE tier_id = ? AND reserved >= ?
                """, q, q, booking.tierId(), q);
        jdbc.update("""
                UPDATE user_tier_quota SET active_quantity = active_quantity - ?, version = version + 1
                WHERE user_id = ? AND tier_id = ? AND active_quantity >= ?
                """, q, booking.userId(), booking.tierId(), q);
        jdbc.update("UPDATE reservations SET status = 'RELEASED', updated_at = now() WHERE booking_id = ?",
                booking.id());
        IntentRow active = activeIntent(booking.id());
        jdbc.update("""
                UPDATE bookings SET status = 'EXPIRED', entitlement_status = 'REVOKED',
                       active_payment_intent_id = NULL, updated_at = now() WHERE id = ?
                """, booking.id());
        if (active != null) {
            jdbc.update("UPDATE payment_intents SET state = 'CANCELED', active = FALSE, updated_at = now() "
                    + "WHERE id = ?", active.id());
            jdbc.update("""
                    UPDATE commands SET state = 'CANCELED', updated_at = now()
                    WHERE provider_key = ? AND state IN ('READY', 'UNKNOWN_QUERY')
                    """, active.providerKey());
            if (isCaptureRunning(active.providerKey())) {
                createVoidCommand(booking.id(), active.providerKey());
            }
        }
        outbox.append("booking", booking.id(), OutboxWriter.TOPIC_BOOKING, "booking.expired", Map.of(
                "bookingId", booking.id().toString(), "userId", booking.userId().toString()));
        publishStatus(booking.id(), "EXPIRED", booking.refundState());
        return true;
    }

    // ------------------------------------------------- dispatcher-driven paths

    /**
     * Capture succeeded at the gateway. If the booking is still awaiting
     * payment this confirms it, issues tickets and moves stock/quota. If the
     * booking already terminated (late or extra capture) the money is
     * compensated with an automatic refund command.
     */
    @Override
    @Transactional
    public String completeCapture(UUID bookingId, String captureProviderKey, long amountMinor, String currency,
            String gatewayRef) {
        BookingRow booking = jdbc.query(LOCK_BOOKING, MAP_BOOKING, bookingId).stream().findFirst().orElse(null);
        if (booking == null) {
            return "booking_missing";
        }
        record IntentRowT(UUID id, String providerKey, Boolean active, Long requested) {
        }
        List<IntentRowT> intents = jdbc.query("""
                SELECT id, provider_key, active, requested_amount_minor FROM payment_intents
                WHERE booking_id = ? AND provider_key = ?
                """, (rs, i) -> new IntentRowT(rs.getObject("id", UUID.class), rs.getString("provider_key"),
                rs.getBoolean("active"), rs.getObject("requested_amount_minor", Long.class)), bookingId,
                captureProviderKey);
        if (intents.isEmpty()) {
            return "intent_missing";
        }
        IntentRowT intent = intents.getFirst();
        jdbc.update("UPDATE payment_intents SET state = 'SUCCEEDED', captured_amount_minor = ?, active = FALSE, "
                + "updated_at = now() WHERE id = ?", amountMinor, intent.id());

        if ("PAYMENT_PENDING".equals(booking.status())
                && Boolean.TRUE.equals(intent.active())
                && booking.activeIntentId() != null && booking.activeIntentId().equals(intent.id())) {
            jdbc.update("""
                    INSERT INTO payment_balance (booking_id, currency, captured_amount_minor) VALUES (?, ?, ?)
                    ON CONFLICT (booking_id) DO UPDATE SET
                      captured_amount_minor = payment_balance.captured_amount_minor + ?,
                      version = payment_balance.version + 1, updated_at = now()
                    """, booking.id(), currency, amountMinor, amountMinor);
            int q = booking.quantity();
            jdbc.update("""
                    UPDATE inventory SET reserved = reserved - ?, sold = sold + ?, version = version + 1
                    WHERE tier_id = ? AND reserved >= ?
                    """, q, q, booking.tierId(), q);
            jdbc.update("""
                    UPDATE user_tier_quota SET active_quantity = active_quantity - ?,
                           confirmed_quantity = confirmed_quantity + ?, version = version + 1
                    WHERE user_id = ? AND tier_id = ? AND active_quantity >= ?
                    """, q, q, booking.userId(), booking.tierId(), q);
            jdbc.update("UPDATE reservations SET status = 'CONSUMED', updated_at = now() WHERE booking_id = ?",
                    booking.id());
            jdbc.update("""
                    UPDATE bookings SET status = 'CONFIRMED', confirmed_at = now(), updated_at = now()
                    WHERE id = ?
                    """, booking.id());
            ticketIssuer.issue(booking.id(), booking.eventId(), booking.userId(), q);
            outbox.append("booking", booking.id(), OutboxWriter.TOPIC_BOOKING, "booking.confirmed", Map.of(
                    "bookingId", booking.id().toString(), "userId", booking.userId().toString(), "amountMinor",
                    amountMinor));
            publishStatus(booking.id(), "CONFIRMED", booking.refundState());
            return "confirmed";
        }
        // Late or extra capture on a terminated order: compensate by refunding
        // the captured amount. The refund command key is derived from the
        // capture provider key so every compensation path shares one command;
        // the NOT EXISTS guard makes the whole block idempotent.
        if (List.of("EXPIRED", "CANCELLED_BEFORE_PAYMENT", "CANCELLED", "PAYMENT_FAILED", "CANCELLATION_PENDING")
                .contains(booking.status())) {
            String refundProviderKey = "rf-late-" + captureProviderKey;
            List<UUID> insertedIds = jdbc.query("""
                    INSERT INTO commands (kind, aggregate_type, aggregate_id, provider_key, target_provider_key)
                    SELECT 'REFUND', 'booking', ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM commands WHERE provider_key = ?)
                    RETURNING id
                    """, (rs, i) -> rs.getObject("id", UUID.class), booking.id(), refundProviderKey,
                    captureProviderKey, refundProviderKey);
            if (!insertedIds.isEmpty()) {
                UUID commandId = insertedIds.getFirst();
                jdbc.update("""
                        INSERT INTO payment_balance (booking_id, currency, captured_amount_minor) VALUES (?, ?, ?)
                        ON CONFLICT (booking_id) DO UPDATE SET
                          captured_amount_minor = payment_balance.captured_amount_minor + ?,
                          version = payment_balance.version + 1, updated_at = now()
                        """, booking.id(), currency, amountMinor, amountMinor);
                jdbc.update("UPDATE bookings SET refund_state = CASE WHEN refund_state = 'NONE' THEN 'PENDING' "
                        + "ELSE refund_state END, updated_at = now() WHERE id = ?", booking.id());
                jdbc.update("""
                        INSERT INTO refunds (payment_id, booking_id, amount_minor, state, command_id)
                        VALUES (?, ?, ?, 'PENDING', ?)
                        ON CONFLICT (command_id) DO NOTHING
                        """, intent.id(), booking.id(), amountMinor, commandId);
                jdbc.update("""
                        UPDATE payment_balance SET refund_reserved_amount_minor = refund_reserved_amount_minor + ?,
                               version = version + 1, updated_at = now()
                        WHERE booking_id = ? AND refund_reserved_amount_minor + refunded_amount_minor + ?
                              <= captured_amount_minor
                        """, amountMinor, booking.id(), amountMinor);
                outbox.append("booking", booking.id(), OutboxWriter.TOPIC_BOOKING,
                        "booking.late_capture_compensated", Map.of("bookingId", booking.id().toString(),
                                "userId", booking.userId().toString(), "amountMinor", amountMinor));
                return "late_capture_refund_created";
            }
            return "late_capture_refund_exists";
        }
        return "no_side_effect";
    }

    @Override
    @Transactional
    public String failCapture(UUID bookingId, String captureProviderKey) {
        BookingRow booking = jdbc.query(LOCK_BOOKING, MAP_BOOKING, bookingId).stream().findFirst().orElse(null);
        if (booking == null) {
            return "booking_missing";
        }
        jdbc.update("""
                UPDATE payment_intents SET state = 'FAILED', active = FALSE, updated_at = now()
                WHERE booking_id = ? AND provider_key = ? AND state <> 'SUCCEEDED'
                """, bookingId, captureProviderKey);
        if ("PAYMENT_PENDING".equals(booking.status())) {
            int q = booking.quantity();
            jdbc.update("""
                    UPDATE inventory SET reserved = reserved - ?, available = available + ?, version = version + 1
                    WHERE tier_id = ? AND reserved >= ?
                    """, q, q, booking.tierId(), q);
            jdbc.update("""
                    UPDATE user_tier_quota SET active_quantity = active_quantity - ?, version = version + 1
                    WHERE user_id = ? AND tier_id = ? AND active_quantity >= ?
                    """, q, booking.userId(), booking.tierId(), q);
            jdbc.update("UPDATE reservations SET status = 'RELEASED', updated_at = now() WHERE booking_id = ?",
                    booking.id());
            jdbc.update("""
                    UPDATE bookings SET status = 'PAYMENT_FAILED', entitlement_status = 'REVOKED',
                           active_payment_intent_id = NULL, updated_at = now() WHERE id = ?
                    """, bookingId);
            outbox.append("booking", bookingId, OutboxWriter.TOPIC_BOOKING, "payment.failed", Map.of(
                    "bookingId", bookingId.toString(), "userId", booking.userId().toString()));
            publishStatus(bookingId, "PAYMENT_FAILED", booking.refundState());
            return "failed";
        }
        return "no_side_effect";
    }

    @Override
    @Transactional
    public String completeVoid(UUID bookingId, String voidProviderKey, String captureProviderKey,
            String voidOutcome) {
        BookingRow booking = jdbc.query(LOCK_BOOKING, MAP_BOOKING, bookingId).stream().findFirst().orElse(null);
        if (booking == null) {
            return "booking_missing";
        }
        jdbc.update("UPDATE payment_intents SET state = 'VOIDED', active = FALSE, updated_at = now() "
                + "WHERE booking_id = ? AND provider_key = ? AND active = TRUE", bookingId, captureProviderKey);
        return "void_" + voidOutcome;
    }

    /** VOID hit an already-captured charge: convert to a refund compensation. */
    @Override
    @Transactional
    public String convertVoidToRefund(UUID bookingId, String captureProviderKey, long amountMinor,
            String currency) {
        BookingRow booking = jdbc.query(LOCK_BOOKING, MAP_BOOKING, bookingId).stream().findFirst().orElse(null);
        if (booking == null) {
            return "booking_missing";
        }
        String refundProviderKey = "rf-late-" + captureProviderKey;
        List<UUID> insertedIds = jdbc.query("""
                INSERT INTO commands (kind, aggregate_type, aggregate_id, provider_key, target_provider_key)
                SELECT 'REFUND', 'booking', ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM commands WHERE provider_key = ?)
                RETURNING id
                """, (rs, i) -> rs.getObject("id", UUID.class), booking.id(), refundProviderKey,
                captureProviderKey, refundProviderKey);
        if (!insertedIds.isEmpty()) {
            jdbc.update("""
                    UPDATE payment_intents SET state = 'SUCCEEDED', captured_amount_minor = ?, active = FALSE,
                           updated_at = now() WHERE booking_id = ? AND provider_key = ?
                    """, amountMinor, bookingId, captureProviderKey);
            jdbc.update("""
                    INSERT INTO payment_balance (booking_id, currency, captured_amount_minor) VALUES (?, ?, ?)
                    ON CONFLICT (booking_id) DO UPDATE SET
                      captured_amount_minor = payment_balance.captured_amount_minor + ?,
                      version = payment_balance.version + 1, updated_at = now()
                    """, booking.id(), currency, amountMinor, amountMinor);
            jdbc.update("UPDATE bookings SET refund_state = CASE WHEN refund_state = 'NONE' THEN 'PENDING' "
                    + "ELSE refund_state END, updated_at = now() WHERE id = ?", booking.id());
            UUID refundId = jdbc.queryForObject("""
                    INSERT INTO refunds (payment_id, booking_id, amount_minor, state, command_id)
                    VALUES ((SELECT id FROM payment_intents WHERE booking_id = ? AND provider_key = ?), ?, ?,
                            'PENDING', ?)
                    RETURNING id
                    """, UUID.class, bookingId, captureProviderKey, booking.id(), amountMinor,
                    insertedIds.getFirst());
            jdbc.update("""
                    UPDATE payment_balance SET refund_reserved_amount_minor = refund_reserved_amount_minor + ?,
                           version = version + 1, updated_at = now()
                    WHERE booking_id = ? AND refund_reserved_amount_minor + refunded_amount_minor + ?
                          <= captured_amount_minor
                    """, amountMinor, booking.id(), amountMinor);
            outbox.append("booking", booking.id(), OutboxWriter.TOPIC_BOOKING,
                    "booking.late_capture_compensated", Map.of("bookingId", booking.id().toString(), "userId",
                            booking.userId().toString(), "amountMinor", amountMinor));
            return "refund_created";
        }
        return "refund_exists";
    }

    // --------------------------------------------------------- refund outcomes

    @Override
    @Transactional
    public void refundSucceeded(UUID refundId, long amountMinor, String providerRef) {
        record Ref(UUID bookingId, String state) {
        }
        Ref refund = jdbc.query("SELECT booking_id, state FROM refunds WHERE id = ? FOR UPDATE", (rs, i) ->
                new Ref(rs.getObject("booking_id", UUID.class), rs.getString("state")), refundId)
                .stream().findFirst().orElse(null);
        if (refund == null || "SUCCEEDED".equals(refund.state())) {
            return;
        }
        int moved = jdbc.update("""
                UPDATE payment_balance SET refund_reserved_amount_minor = refund_reserved_amount_minor - ?,
                       refunded_amount_minor = refunded_amount_minor + ?, version = version + 1,
                       updated_at = now()
                WHERE booking_id = ? AND refund_reserved_amount_minor >= ?
                """, amountMinor, amountMinor, refund.bookingId(), amountMinor);
        if (moved == 0) {
            // Reserved amount vanished: this must not happen; escalate instead
            // of silently violating the balance invariant.
            jdbc.update("UPDATE refunds SET state = 'MANUAL_REVIEW', updated_at = now() WHERE id = ?", refundId);
            jdbc.update("UPDATE bookings SET refund_state = 'MANUAL_REVIEW', updated_at = now() WHERE id = ?",
                    refund.bookingId());
            log.error("refund reservation missing for refund {}", refundId);
            return;
        }
        jdbc.update("UPDATE refunds SET state = 'SUCCEEDED', provider_ref = ?, updated_at = now() WHERE id = ?",
                providerRef, refundId);
        boolean fullyRefunded = jdbc.queryForObject("""
                SELECT refund_reserved_amount_minor = 0 AND refunded_amount_minor >= captured_amount_minor
                FROM payment_balance WHERE booking_id = ?
                """, Boolean.class, refund.bookingId());
        if (fullyRefunded) {
            int completed = jdbc.update("""
                    UPDATE bookings SET status = 'CANCELLED', refund_state = 'REFUNDED', cancelled_at = now(),
                           updated_at = now() WHERE id = ? AND status = 'CANCELLATION_PENDING'
                    """, refund.bookingId());
            if (completed == 0) {
                // Late-capture compensation on an already-terminated order:
                // the money is fully refunded, the order keeps its terminal status.
                jdbc.update("UPDATE bookings SET refund_state = 'REFUNDED', updated_at = now() WHERE id = ?",
                        refund.bookingId());
            }
        }
        else {
            jdbc.update("UPDATE bookings SET refund_state = 'REFUNDED', updated_at = now() WHERE id = ?",
                    refund.bookingId());
        }
        outbox.append("booking", refund.bookingId(), OutboxWriter.TOPIC_BOOKING, "refund.succeeded", Map.of(
                "bookingId", refund.bookingId().toString(), "refundId", refundId.toString(), "amountMinor",
                amountMinor));
        publishStatus(refund.bookingId(), fullyRefunded ? "CANCELLED" : "CANCELLATION_PENDING", "REFUNDED");
    }

    @Override
    @Transactional
    public void refundFailed(UUID refundId, boolean manualReview) {
        record Ref(UUID bookingId, String state) {
        }
        Ref refund = jdbc.query("SELECT booking_id, state FROM refunds WHERE id = ? FOR UPDATE", (rs, i) ->
                new Ref(rs.getObject("booking_id", UUID.class), rs.getString("state")), refundId)
                .stream().findFirst().orElse(null);
        if (refund == null || "SUCCEEDED".equals(refund.state())) {
            return;
        }
        // Keep the reservation: the money is still owed to the customer.
        String refundState = manualReview ? "MANUAL_REVIEW" : "REFUND_FAILED";
        jdbc.update("UPDATE refunds SET state = ?, updated_at = now() WHERE id = ? AND state <> 'SUCCEEDED'",
                manualReview ? "MANUAL_REVIEW" : "FAILED", refundId);
        jdbc.update("UPDATE bookings SET refund_state = ?, updated_at = now() WHERE id = ?", refundState,
                refund.bookingId());
        if (manualReview) {
            outbox.append("booking", refund.bookingId(), OutboxWriter.TOPIC_BOOKING, "refund.failed", Map.of(
                    "bookingId", refund.bookingId().toString(), "refundId", refundId.toString(), "manualReview",
                    true));
        }
    }

    // ----------------------------------------------------------------- helpers

    private IntentRow activeIntent(UUID bookingId) {
        return jdbc.query("""
                SELECT id, attempt_no, state, requested_amount_minor, captured_amount_minor, currency,
                       provider_key, active FROM payment_intents WHERE booking_id = ? AND active = TRUE
                """, (rs, i) -> new IntentRow(rs.getObject("id", UUID.class), rs.getInt("attempt_no"),
                rs.getString("state"), rs.getObject("requested_amount_minor", Long.class),
                rs.getObject("captured_amount_minor", Long.class), rs.getString("currency"),
                rs.getString("provider_key"), rs.getBoolean("active")), bookingId).stream().findFirst()
                .orElse(null);
    }

    private boolean isCaptureRunning(String captureProviderKey) {
        Integer running = jdbc.queryForObject("""
                SELECT COUNT(*) FROM commands WHERE provider_key = ? AND state = 'RUNNING'
                """, Integer.class, captureProviderKey);
        return running != null && running > 0;
    }

    private void createVoidCommand(UUID bookingId, String captureProviderKey) {
        jdbc.update("""
                INSERT INTO commands (kind, aggregate_type, aggregate_id, provider_key, target_provider_key)
                SELECT 'VOID', 'booking', ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM commands WHERE provider_key = ?)
                """, bookingId, "vd-" + captureProviderKey, captureProviderKey, "vd-" + captureProviderKey);
    }
}