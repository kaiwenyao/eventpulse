package dev.kaiwen.eventpulse.booking;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.booking.BookingDtos.BatchResult;
import dev.kaiwen.eventpulse.booking.BookingDtos.BookingView;
import dev.kaiwen.eventpulse.booking.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.booking.BookingDtos.IntentRow;
import dev.kaiwen.eventpulse.booking.BookingDtos.RefundView;
import dev.kaiwen.eventpulse.booking.BookingDtos.TicketView;
import dev.kaiwen.eventpulse.booking.BookingDtos.ViewRow;
import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.common.DbClock;
import dev.kaiwen.eventpulse.common.error.ApiException;
import dev.kaiwen.eventpulse.common.error.ErrorCode;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.ticketing.TicketIssuer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Booking and payment-intent orchestration. Every state transition re-locks
 * rows in the fixed protocol-B order (booking, quota, inventory, reservation,
 * tickets, payment balance) and re-validates status and version afterwards:
 * the loser of a race re-reads and returns a no-side-effect result.
 */
@Service
public class BookingService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final IdempotencyService idempotency;
    private final OutboxWriter outbox;
    private final AppProperties properties;
    private final DbClock clock;
    private final TicketIssuer ticketIssuer;

    public BookingService(JdbcTemplate jdbc, TransactionTemplate tx, IdempotencyService idempotency,
            OutboxWriter outbox, AppProperties properties, DbClock clock, TicketIssuer ticketIssuer) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
        this.ticketIssuer = ticketIssuer;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public BookingView createBooking(UUID userId, String rawIdempotencyKey, CreateBookingRequest request) {
        if (request.eventId() == null || request.tierId() == null || request.quantity() == null
                || request.quantity() < 1 || request.quantity() > properties.booking().maxPerBooking()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid booking request");
        }
        Map<String, Object> fingerprintBody = Map.of(
                "eventId", String.valueOf(request.eventId()),
                "tierId", String.valueOf(request.tierId()),
                "quantity", request.quantity(),
                "ageConfirmed", Boolean.TRUE.equals(request.ageConfirmed()));
        IdempotencyService.Fingerprint fingerprint = idempotency.claim(userId, "bookings:create",
                rawIdempotencyKey, fingerprintBody);

        record EventRow(UUID id, Integer ageRequirement, String policy) {
        }
        List<EventRow> events = jdbc.query("SELECT id, age_requirement, policy::text AS policy FROM events "
                + "WHERE id = ? AND status = 'PUBLISHED'", (rs, i) -> new EventRow(rs.getObject("id", UUID.class),
                rs.getObject("age_requirement", Integer.class), rs.getString("policy")), request.eventId());
        if (events.isEmpty()) {
            throw ApiException.notFound();
        }
        EventRow event = events.getFirst();

        record TierRow(UUID id, Long unitPriceMinor, String currency, Instant saleStart, Instant saleEnd,
                       Integer perUserLimit) {
        }
        List<TierRow> tiers = jdbc.query("""
                SELECT id, unit_price_minor, currency, sale_start_at, sale_end_at, per_user_limit
                FROM ticket_tiers WHERE id = ? AND event_id = ? AND status = 'ACTIVE'
                """, (rs, i) -> new TierRow(rs.getObject("id", UUID.class), rs.getObject("unit_price_minor",
                Long.class), rs.getString("currency"), rs.getObject("sale_start_at", OffsetDateTime.class).toInstant(),
                rs.getObject("sale_end_at", OffsetDateTime.class).toInstant(), rs.getObject("per_user_limit", Integer.class)),
                request.tierId(), request.eventId());
        if (tiers.isEmpty()) {
            throw ApiException.notFound();
        }
        TierRow tier = tiers.getFirst();

        Instant now = clock.now();
        if (now.isBefore(tier.saleStart()) || now.isAfter(tier.saleEnd())) {
            throw new ApiException(ErrorCode.SALE_WINDOW_CLOSED, "ticket sale window is closed");
        }

        // Age eligibility: a requirement must be covered by a verified fact;
        // an unknown requirement can only pass with an explicit confirmation.
        Integer requiredAge = event.ageRequirement();
        if (requiredAge != null) {
            // query() returns an empty list when no eligibility row exists, so a
            // user with no verified fact is treated as "unverified" rather than
            // surfacing an EmptyResultDataAccessException as a 500.
            List<Integer> facts = jdbc.queryForList(
                    "SELECT minimum_verified_age FROM user_eligibility WHERE user_id = ? "
                            + "AND (expires_at IS NULL OR expires_at > now()) ORDER BY minimum_verified_age DESC",
                    Integer.class, userId);
            Integer verified = facts.isEmpty() ? null : facts.get(0);
            if (verified == null || verified < requiredAge) {
                throw new ApiException(ErrorCode.AGE_REQUIREMENT_NOT_CONFIRMED,
                        "age requirement not satisfied by a verified fact");
            }
        }
        else if (!Boolean.TRUE.equals(request.ageConfirmed())) {
            throw new ApiException(ErrorCode.AGE_REQUIREMENT_NOT_CONFIRMED,
                    "age requirement unknown; explicit confirmation required");
        }

        long total = tier.unitPriceMinor() * request.quantity();
        Map<String, Object> policySnapshot = defaultPolicySnapshot(event.policy());
        Map<String, Object> priceSnapshot = Map.of(
                "unitPriceMinor", tier.unitPriceMinor(),
                "currency", tier.currency(),
                "quantity", request.quantity(),
                "totalMinor", total,
                "policyVersion", policySnapshot.get("policyVersion"));

        // Protocol A: UPSERT quota, lock quota, lock inventory, conditional
        // updates, then insert booking/reservation/outbox. Never waits on an
        // existing booking row.
        jdbc.update("""
                INSERT INTO user_tier_quota (user_id, tier_id) VALUES (?, ?)
                ON CONFLICT (user_id, tier_id) DO NOTHING
                """, userId, request.tierId());
        jdbc.queryForObject("""
                SELECT id FROM user_tier_quota WHERE user_id = ? AND tier_id = ? FOR UPDATE
                """, UUID.class, userId, request.tierId());
        jdbc.queryForObject("SELECT tier_id FROM inventory WHERE tier_id = ? FOR UPDATE", UUID.class,
                request.tierId());

        int inventoryRows = jdbc.update("""
                UPDATE inventory SET available = available - ?, reserved = reserved + ?, version = version + 1
                WHERE tier_id = ? AND available >= ?
                """, request.quantity(), request.quantity(), request.tierId(), request.quantity());
        if (inventoryRows == 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_INVENTORY, "not enough tickets available");
        }
        int quotaRows = jdbc.update("""
                UPDATE user_tier_quota SET active_quantity = active_quantity + ?, version = version + 1
                WHERE user_id = ? AND tier_id = ?
                  AND active_quantity + ? + confirmed_quantity <= ?
                """, request.quantity(), userId, request.tierId(), request.quantity(), tier.perUserLimit());
        if (quotaRows == 0) {
            throw new ApiException(ErrorCode.PER_USER_LIMIT_EXCEEDED,
                    "per-user purchase limit reached for this tier");
        }

        UUID bookingId = jdbc.queryForObject("""
                INSERT INTO bookings (user_id, event_id, tier_id, quantity, status, entitlement_status,
                                      unit_price_minor, currency, expires_at, price_snapshot, policy_snapshot)
                VALUES (?, ?, ?, ?, 'PAYMENT_PENDING', 'ACTIVE', ?, ?, now() + make_interval(secs => ?),
                        ?::jsonb, ?::jsonb)
                RETURNING id
                """, UUID.class, userId, request.eventId(), request.tierId(), request.quantity(),
                tier.unitPriceMinor(), tier.currency(), properties.booking().ttl().toSeconds(),
                CanonicalJson.canonicalize(priceSnapshot, dev.kaiwen.eventpulse.outbox.OutboxJson.mapper()),
                CanonicalJson.canonicalize(policySnapshot, dev.kaiwen.eventpulse.outbox.OutboxJson.mapper()));
        jdbc.update("INSERT INTO reservations (booking_id, tier_id, quantity, status) VALUES (?, ?, ?, 'ACTIVE')",
                bookingId, request.tierId(), request.quantity());
        outbox.append("booking", bookingId, OutboxWriter.TOPIC_BOOKING, "booking.created", Map.of(
                "bookingId", bookingId.toString(), "userId", userId.toString(), "eventId",
                request.eventId().toString(), "tierId", request.tierId().toString(), "quantity",
                request.quantity()));

        BookingView view = getBooking(userId, bookingId);
        idempotency.complete(fingerprint, 201, view);
        return view;
    }

    // -------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public BookingView getBooking(UUID userId, UUID bookingId) {
        List<ViewRow> rows = jdbc.query(BookingServiceSql.SELECT_BOOKING_FOR_USER, (rs, i) ->
                new ViewRow(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                        rs.getObject("tier_id", UUID.class), rs.getString("tier_name"), rs.getInt("quantity"),
                        rs.getString("status"), rs.getString("entitlement_status"), rs.getString("refund_state"),
                        rs.getObject("unit_price_minor", Long.class), rs.getString("currency"),
                        rs.getString("price_snapshot"), rs.getString("policy_snapshot"), rs.getString("expires_at"),
                        rs.getString("confirmed_at"), rs.getString("cancelled_at")), userId, bookingId);
        if (rows.isEmpty()) {
            throw ApiException.notFound(); // object-existence and access are indistinguishable
        }
        ViewRow row = rows.getFirst();
        List<IntentRow> intents = jdbc.query("""
                SELECT id, attempt_no, state, requested_amount_minor, captured_amount_minor, currency,
                       provider_key, active
                FROM payment_intents WHERE booking_id = ? ORDER BY attempt_no
                """, (rs, i) -> new IntentRow(rs.getObject("id", UUID.class), rs.getInt("attempt_no"),
                rs.getString("state"), rs.getObject("requested_amount_minor", Long.class),
                rs.getObject("captured_amount_minor", Long.class), rs.getString("currency"),
                rs.getString("provider_key"), rs.getBoolean("active")), bookingId);
        List<RefundView> refunds = jdbc.query("""
                SELECT id, amount_minor, state, created_at::text FROM refunds WHERE booking_id = ?
                ORDER BY created_at
                """, (rs, i) -> new RefundView(rs.getObject("id", UUID.class), rs.getObject("amount_minor",
                Long.class), rs.getString("state"), rs.getString("created_at")), bookingId);
        List<TicketView> tickets = jdbc.query("""
                SELECT id, sequence, status, used_at::text FROM tickets WHERE booking_id = ?
                ORDER BY sequence
                """, (rs, i) -> new TicketView(rs.getObject("id", UUID.class), rs.getInt("sequence"),
                rs.getString("status"), rs.getString("used_at")), bookingId);
        return toView(row, intents, refunds, tickets);
    }

    public static BookingView toView(ViewRow row, List<IntentRow> intents, List<RefundView> refunds,
            List<TicketView> tickets) {
        Map<String, Object> price = parse(row.priceSnapshot());
        Map<String, Object> policy = parse(row.policySnapshot());
        IntentRow activeIntent = intents.stream().filter(IntentRow::active).findFirst().orElse(null);
        return new BookingView(row.id(), row.eventId(), row.tierId(), row.tierName(), row.quantity(),
                row.status(), row.entitlementStatus(), row.refundState(), row.unitPriceMinor(), row.currency(),
                price.get("totalMinor") instanceof Number n ? n.longValue() : null, price, policy,
                row.expiresAt(), row.confirmedAt(), row.cancelledAt(),
                activeIntent == null ? null : new BookingDtos.PaymentIntentView(activeIntent.id(),
                        activeIntent.attemptNo(), activeIntent.state(), activeIntent.requestedAmountMinor(),
                        activeIntent.capturedAmountMinor(), activeIntent.currency(), activeIntent.providerKey(),
                        activeIntent.active()),
                refunds, tickets);
    }

    private static Map<String, Object> parse(String json) {
        if (json == null) {
            return Map.of();
        }
        return dev.kaiwen.eventpulse.outbox.OutboxJson.mapper().readValue(json, Map.class);
    }

    private Map<String, Object> defaultPolicySnapshot(String eventPolicy) {
        Map<String, Object> policy = eventPolicy == null ? Map.of()
                : dev.kaiwen.eventpulse.outbox.OutboxJson.mapper().readValue(eventPolicy, Map.class);
        Map<String, Object> snapshot = new java.util.HashMap<>();
        snapshot.put("cancellable", Boolean.TRUE.equals(policy.get("cancellable")));
        Object deadline = policy.get("cancellationDeadlineHoursBeforeStart");
        snapshot.put("cancellationDeadlineHoursBeforeStart", deadline instanceof Number n ? n.intValue() : 0);
        snapshot.put("resaleAllowed", Boolean.TRUE.equals(policy.get("resaleAllowed")));
        snapshot.put("policyVersion", policy.getOrDefault("version", 1));
        return snapshot;
    }


    /** SQL kept in one place for reuse by the cancellation batch. */
    public static final class BookingServiceSql {
        public static final String SELECT_BOOKING_FOR_USER = """
                SELECT b.id, b.event_id, b.tier_id, t.name AS tier_name, b.quantity, b.status,
                       b.entitlement_status, b.refund_state, b.unit_price_minor, b.currency,
                       b.price_snapshot::text AS price_snapshot, b.policy_snapshot::text AS policy_snapshot,
                       b.expires_at::text, b.confirmed_at::text, b.cancelled_at::text
                FROM bookings b JOIN ticket_tiers t ON t.id = b.tier_id
                WHERE b.user_id = ? AND b.id = ?
                """;
    }
}
