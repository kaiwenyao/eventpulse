package dev.kaiwen.eventpulse.service.impl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.common.DbClock;
import dev.kaiwen.eventpulse.dto.BookingDtos.IntentRow;
import dev.kaiwen.eventpulse.dto.BookingDtos.PaymentIntentView;
import dev.kaiwen.eventpulse.exception.ApiException;
import dev.kaiwen.eventpulse.exception.ErrorCode;
import dev.kaiwen.eventpulse.outbox.OutboxJson;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.service.BookingTransitions;
import dev.kaiwen.eventpulse.service.TicketIssuer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Protocol-B state transitions. Every entry point locks rows in the fixed
 * order booking → quota → inventory → reservation → tickets → payment
 * balance → user_wallet, then re-validates status. Exactly one racing
 * transition wins; losers return without side effects. Wallet debit/credit
 * happens in the same transaction as the booking migration.
 */
@Service
public class BookingTransitionsImpl implements BookingTransitions {

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

    /**
     * Single-flight wallet capture: debit the user wallet and confirm the
     * booking in this transaction. A second request after success is rejected
     * because the booking is no longer PAYMENT_PENDING.
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

        long amount = totalMinor(booking);
        lockQuotaThenInventory(booking.userId(), booking.tierId());
        jdbc.update("""
                INSERT INTO payment_balance (booking_id, currency) VALUES (?, ?)
                ON CONFLICT (booking_id) DO NOTHING
                """, bookingId, booking.currency());
        jdbc.query("SELECT booking_id FROM payment_balance WHERE booking_id = ? FOR UPDATE",
                (rs, i) -> rs.getObject("booking_id", UUID.class), bookingId);
        jdbc.query("SELECT user_id FROM user_wallets WHERE user_id = ? FOR UPDATE",
                (rs, i) -> rs.getObject("user_id", UUID.class), booking.userId());

        int debit = jdbc.update("""
                UPDATE user_wallets SET available_amount_minor = available_amount_minor - ?,
                       version = version + 1, updated_at = now()
                WHERE user_id = ? AND available_amount_minor >= ?
                """, amount, booking.userId(), amount);
        if (debit == 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "wallet balance is insufficient");
        }

        Integer attemptNo = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM payment_intents WHERE booking_id = ?",
                Integer.class, bookingId);
        String providerKey = "pi-" + CanonicalJson.newOpaqueToken();
        UUID intentId = jdbc.queryForObject("""
                INSERT INTO payment_intents (booking_id, attempt_no, state, requested_amount_minor, currency,
                                             provider_key, active, captured_amount_minor)
                VALUES (?, ?, 'SUCCEEDED', ?, ?, ?, FALSE, ?)
                RETURNING id
                """, UUID.class, bookingId, attemptNo, amount, booking.currency(), providerKey, amount);
        jdbc.update("UPDATE bookings SET active_payment_intent_id = ?, updated_at = now() WHERE id = ?",
                intentId, bookingId);
        confirmBooking(booking, amount, booking.currency());
        outbox.append("booking", bookingId, OutboxWriter.TOPIC_BOOKING, "payment.intent_created", Map.of(
                "bookingId", bookingId.toString(), "userId", booking.userId().toString(),
                "intentId", intentId.toString(), "providerKey", providerKey,
                "amountMinor", amount));
        return new PaymentIntentView(intentId, attemptNo, "SUCCEEDED", amount, amount,
                booking.currency(), providerKey, false);
    }

    private void confirmBooking(BookingRow booking, long amountMinor, String currency) {
        int q = booking.quantity();
        jdbc.update("""
                INSERT INTO payment_balance (booking_id, currency, captured_amount_minor) VALUES (?, ?, ?)
                ON CONFLICT (booking_id) DO UPDATE SET
                  captured_amount_minor = payment_balance.captured_amount_minor + ?,
                  version = payment_balance.version + 1, updated_at = now()
                """, booking.id(), currency, amountMinor, amountMinor);
        jdbc.update("""
                UPDATE user_tier_quota SET active_quantity = active_quantity - ?,
                       confirmed_quantity = confirmed_quantity + ?, version = version + 1
                WHERE user_id = ? AND tier_id = ? AND active_quantity >= ?
                """, q, q, booking.userId(), booking.tierId(), q);
        jdbc.update("""
                UPDATE inventory SET reserved = reserved - ?, sold = sold + ?, version = version + 1
                WHERE tier_id = ? AND reserved >= ?
                """, q, q, booking.tierId(), q);
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
    }

    private long totalMinor(BookingRow booking) {
        return booking.unitPriceMinor() * booking.quantity();
    }

    @Override
    @Transactional
    public boolean cancel(UUID actorId, UUID bookingId, boolean eventCancelled, String actorKind) {
        BookingRow booking = jdbc.query(LOCK_BOOKING, MAP_BOOKING, bookingId).stream().findFirst()
                .orElseThrow(ApiException::notFound);
        switch (booking.status()) {
            case "PAYMENT_PENDING" -> cancelBeforePayment(booking);
            case "CONFIRMED" -> cancelConfirmed(booking, eventCancelled);
            case "CANCELLATION_PENDING", "CANCELLED", "CANCELLED_BEFORE_PAYMENT", "EXPIRED" -> {
                return false;
            }
            default -> throw new ApiException(ErrorCode.BOOKING_NOT_CANCELLABLE,
                    "booking cannot be cancelled in state " + booking.status());
        }
        return true;
    }

    private void cancelBeforePayment(BookingRow booking) {
        int q = booking.quantity();
        lockQuotaThenInventory(booking.userId(), booking.tierId());
        jdbc.update("""
                UPDATE user_tier_quota SET active_quantity = active_quantity - ?, version = version + 1
                WHERE user_id = ? AND tier_id = ? AND active_quantity >= ?
                """, q, booking.userId(), booking.tierId(), q);
        jdbc.update("""
                UPDATE inventory SET reserved = reserved - ?, available = available + ?, version = version + 1
                WHERE tier_id = ? AND reserved >= ?
                """, q, q, booking.tierId(), q);
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
        lockQuotaThenInventory(booking.userId(), booking.tierId());
        jdbc.update("UPDATE tickets SET status = 'REVOKED' WHERE booking_id = ? AND status = 'ACTIVE'",
                booking.id());
        jdbc.update("""
                UPDATE reservations SET status = ?, updated_at = now() WHERE booking_id = ?
                """, resaleAllowed ? "RELEASED" : "WITHHELD", booking.id());
        jdbc.update("""
                UPDATE user_tier_quota SET confirmed_quantity = confirmed_quantity - ?, version = version + 1
                WHERE user_id = ? AND tier_id = ? AND confirmed_quantity >= ?
                """, q, booking.userId(), booking.tierId(), q);
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
                INSERT INTO payment_balance (booking_id, currency) VALUES (?, ?)
                ON CONFLICT (booking_id) DO NOTHING
                """, booking.id(), booking.currency());
        jdbc.query("SELECT booking_id FROM payment_balance WHERE booking_id = ? FOR UPDATE",
                (rs, i) -> rs.getObject("booking_id", UUID.class), booking.id());
        long refundable = jdbc.queryForObject("""
                SELECT captured_amount_minor - refund_reserved_amount_minor - refunded_amount_minor
                FROM payment_balance WHERE booking_id = ?
                """, Long.class, booking.id());
        if (refundable <= 0) {
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
        jdbc.query("SELECT user_id FROM user_wallets WHERE user_id = ? FOR UPDATE",
                (rs, i) -> rs.getObject("user_id", UUID.class), booking.userId());
        jdbc.update("""
                UPDATE user_wallets SET available_amount_minor = available_amount_minor + ?,
                       version = version + 1, updated_at = now()
                WHERE user_id = ?
                """, refundable, booking.userId());
        jdbc.update("""
                UPDATE payment_balance SET refund_reserved_amount_minor = refund_reserved_amount_minor - ?,
                       refunded_amount_minor = refunded_amount_minor + ?, version = version + 1,
                       updated_at = now()
                WHERE booking_id = ? AND refund_reserved_amount_minor >= ?
                """, refundable, refundable, booking.id(), refundable);

        IntentRow intent = activeIntent(booking.id());
        UUID intentId = intent != null ? intent.id() : jdbc.queryForObject(
                "SELECT id FROM payment_intents WHERE booking_id = ? ORDER BY attempt_no DESC LIMIT 1",
                UUID.class, booking.id());
        UUID refundId = jdbc.queryForObject("""
                INSERT INTO refunds (payment_id, booking_id, amount_minor, state, provider_ref)
                VALUES (?, ?, ?, 'SUCCEEDED', ?)
                RETURNING id
                """, UUID.class, intentId, booking.id(), refundable, "wallet-" + booking.id());
        jdbc.update("""
                UPDATE bookings SET status = 'CANCELLED', entitlement_status = 'REVOKED',
                       refund_state = 'REFUNDED', cancelled_at = now(), updated_at = now()
                WHERE id = ?
                """, booking.id());
        outbox.append("booking", booking.id(), OutboxWriter.TOPIC_BOOKING, "refund.succeeded", Map.of(
                "bookingId", booking.id().toString(), "refundId", refundId.toString(), "amountMinor",
                refundable));
        outbox.append("booking", booking.id(), OutboxWriter.TOPIC_BOOKING, "booking.cancelled", Map.of(
                "bookingId", booking.id().toString(), "userId", booking.userId().toString(), "phase",
                "refunded"));
        publishStatus(booking.id(), "CANCELLED", "REFUNDED");
    }

    @Override
    @Transactional
    public boolean expireBooking(UUID bookingId) {
        BookingRow booking = jdbc.query(LOCK_BOOKING, MAP_BOOKING, bookingId).stream().findFirst().orElse(null);
        if (booking == null || !"PAYMENT_PENDING".equals(booking.status())) {
            return false;
        }
        if (booking.expiresAt() == null || clock.now().isBefore(booking.expiresAt())) {
            return false;
        }
        int q = booking.quantity();
        lockQuotaThenInventory(booking.userId(), booking.tierId());
        jdbc.update("""
                UPDATE user_tier_quota SET active_quantity = active_quantity - ?, version = version + 1
                WHERE user_id = ? AND tier_id = ? AND active_quantity >= ?
                """, q, booking.userId(), booking.tierId(), q);
        jdbc.update("""
                UPDATE inventory SET reserved = reserved - ?, available = available + ?, version = version + 1
                WHERE tier_id = ? AND reserved >= ?
                """, q, q, booking.tierId(), q);
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
        }
        outbox.append("booking", booking.id(), OutboxWriter.TOPIC_BOOKING, "booking.expired", Map.of(
                "bookingId", booking.id().toString(), "userId", booking.userId().toString()));
        publishStatus(booking.id(), "EXPIRED", booking.refundState());
        return true;
    }

    private void lockQuotaThenInventory(UUID userId, UUID tierId) {
        jdbc.query("SELECT id FROM user_tier_quota WHERE user_id = ? AND tier_id = ? FOR UPDATE",
                (rs, i) -> rs.getObject("id", UUID.class), userId, tierId);
        jdbc.query("SELECT tier_id FROM inventory WHERE tier_id = ? FOR UPDATE", (rs, i) ->
                rs.getObject("tier_id", UUID.class), tierId);
    }

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
}
