package dev.kaiwen.eventpulse.service.impl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.common.DbClock;
import dev.kaiwen.eventpulse.common.web.RateLimiter;
import dev.kaiwen.eventpulse.dto.BookingDtos;
import dev.kaiwen.eventpulse.dto.BookingDtos.RedeemResult;
import dev.kaiwen.eventpulse.exception.ApiException;
import dev.kaiwen.eventpulse.exception.ErrorCode;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.service.IdempotencyService;
import dev.kaiwen.eventpulse.service.TicketService;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tickets are CSPRNG values with >= 128 bits of entropy; only an HMAC
 * (server pepper) hash is persisted. The raw token is exposed exactly once
 * through an authorized reveal call and never enters URLs, logs, Kafka
 * payloads or analytics. Redemption is an atomic ACTIVE -> USED migration
 * under the protocol-B lock order (booking first, then ticket), so a
 * concurrent cancel and a scan cannot both win; repeated scans of the same
 * token return the original result via request-fingerprint idempotency.
 */
@Service
public class TicketServiceImpl implements TicketService {

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final IdempotencyService idempotency;
    private final RateLimiter rateLimiter;
    private final StringRedisTemplate redis;
    private final AppProperties properties;
    private final DbClock clock;

    public TicketServiceImpl(JdbcTemplate jdbc, OutboxWriter outbox, IdempotencyService idempotency,
            RateLimiter rateLimiter, StringRedisTemplate redis, AppProperties properties, DbClock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------- issue

    @Override
    @Transactional
    public void issue(UUID bookingId, UUID eventId, UUID userId, int quantity) {
        List<String> rawTokens = new ArrayList<>();
        for (int sequence = 1; sequence <= quantity; sequence++) {
            String raw = CanonicalJson.newTicketToken();
            String hash = CanonicalJson.hmacSha256Hex(properties.security().tokenPepper(), raw);
            jdbc.update("""
                    INSERT INTO tickets (booking_id, sequence, status, token_hash) VALUES (?, ?, 'ACTIVE', ?)
                    """, bookingId, sequence, hash);
            rawTokens.add(raw);
            outbox.append("ticket", bookingId, OutboxWriter.TOPIC_BOOKING, "ticket.issued", Map.of(
                    "bookingId", bookingId.toString(), "eventId", eventId.toString(), "sequence", sequence));
        }
        // Raw tokens are kept only in the one-time reveal store (Redis with an
        // in-memory fallback), never in the database, logs or events.
        storeRevealTokens(bookingId, rawTokens);
    }

    private void storeRevealTokens(UUID bookingId, List<String> rawTokens) {
        try {
            org.springframework.data.redis.core.ListOperations<String, String> ops = redis.opsForList();
            String key = REVEAL_PREFIX + bookingId;
            ops.rightPushAll(key, rawTokens.toArray(new String[0]));
            redis.expire(key, java.time.Duration.ofDays(7));
        }
        catch (Exception redisDown) {
            InMemoryRevealStore.addAll(bookingId, rawTokens);
        }
    }

    @Override
    public List<String> revealTokens(UUID userId, UUID bookingId) {
        requireOwner(userId, bookingId);
        try {
            String key = REVEAL_PREFIX + bookingId;
            Long size = redis.opsForList().size(key);
            if (size != null && size > 0) {
                return redis.opsForList().range(key, 0, -1);
            }
        }
        catch (Exception redisDown) {
            // fall through to in-memory store
        }
        return InMemoryRevealStore.get(bookingId);
    }

    // ------------------------------------------------------------------ redeem

    @Override
    @Transactional
    public RedeemResult redeem(UUID organiserUserId, String rawToken, String rawIdempotencyKey) {
        rateLimiter.check("redeem", organiserUserId.toString());
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "token required");
        }
        IdempotencyService.Fingerprint fingerprint = idempotency.claim(organiserUserId, "tickets:redeem",
                rawIdempotencyKey, Map.of("token", rawToken));

        String tokenHash = CanonicalJson.hmacSha256Hex(properties.security().tokenPepper(), rawToken);
        record TicketRow(UUID id, UUID bookingId, Integer sequence, String status) {
        }
        List<TicketRow> tickets = jdbc.query("""
                SELECT id, booking_id, sequence, status FROM tickets WHERE token_hash = ?
                """, (rs, i) -> new TicketRow(rs.getObject("id", UUID.class), rs.getObject("booking_id",
                UUID.class), rs.getInt("sequence"), rs.getString("status")), tokenHash);
        if (tickets.isEmpty()) {
            throw ApiException.notFound();
        }
        TicketRow ticket = tickets.getFirst();

        record BookingRow(UUID id, UUID eventId, String bookingStatus, UUID organiserId, String eventTitle,
                          Instant startsAt, Instant endsAt, String eventStatus) {
        }
        List<BookingRow> bookings = jdbc.query("""
                SELECT b.id, b.event_id, b.status, e.organiser_id, e.title, e.starts_at, e.ends_at, e.status
                       AS event_status
                FROM bookings b JOIN events e ON e.id = b.event_id
                WHERE b.id = ?
                """, (rs, i) -> new BookingRow(rs.getObject("id", UUID.class), rs.getObject("event_id",
                UUID.class), rs.getString("status"), rs.getObject("organiser_id", UUID.class),
                rs.getString("title"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant(), rs.getString("event_status")), ticket.bookingId());
        if (bookings.isEmpty()) {
            throw ApiException.notFound();
        }
        BookingRow booking = bookings.getFirst();
        // Owner authorization: the ticket must belong to the organiser's event.
        List<UUID> owned = jdbc.queryForList("SELECT id FROM organisers WHERE owner_user_id = ?", UUID.class,
                organiserUserId);
        if (!owned.contains(booking.organiserId())) {
            throw ApiException.notFound();
        }

        // Protocol B order: lock booking, then the ticket; re-validate both.
        String bookingStatus = jdbc.queryForObject("SELECT status FROM bookings WHERE id = ? FOR UPDATE",
                String.class, booking.id());
        if (!"CONFIRMED".equals(bookingStatus)) {
            throw new ApiException(ErrorCode.TICKET_NOT_REDEEMABLE, "ticket is not redeemable");
        }
        if ("CANCELLED".equals(booking.eventStatus())) {
            throw new ApiException(ErrorCode.TICKET_NOT_REDEEMABLE, "ticket is not redeemable");
        }
        Instant now = clock.now();
        if (now.isBefore(booking.startsAt().minus(java.time.Duration.ofHours(6)))
                || now.isAfter(booking.endsAt())) {
            throw new ApiException(ErrorCode.TICKET_NOT_REDEEMABLE, "ticket is not redeemable");
        }

        int updated = jdbc.update("""
                UPDATE tickets SET status = 'USED', used_at = now(), used_by = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, organiserUserId, ticket.id());
        if (updated == 0) {
            // Non-enumerable business error for USED/REVOKED tickets.
            throw new ApiException(ErrorCode.TICKET_NOT_REDEEMABLE, "ticket is not redeemable");
        }
        RedeemResult result = new RedeemResult("OK", ticket.id(), booking.id(),
                booking.eventId(), booking.eventTitle(), ticket.sequence(), now.toString());
        outbox.append("ticket", ticket.id(), OutboxWriter.TOPIC_BOOKING, "ticket.redeemed", Map.of(
                "ticketId", ticket.id().toString(), "bookingId", booking.id().toString(), "organiserId",
                organiserUserId.toString()));
        idempotency.complete(fingerprint, 200, result);
        return result;
    }

    private void requireOwner(UUID userId, UUID bookingId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE id = ? AND user_id = ?",
                Integer.class, bookingId, userId);
        if (count == null || count == 0) {
            throw ApiException.notFound();
        }
    }

    /** Fallback reveal store when Redis is unreachable (single node only). */
    public static final class InMemoryRevealStore {
        private static final Map<UUID, List<String>> STORE = new java.util.concurrent.ConcurrentHashMap<>();

        static void addAll(UUID bookingId, List<String> tokens) {
            STORE.computeIfAbsent(bookingId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .addAll(tokens);
        }

        static List<String> get(UUID bookingId) {
            return STORE.getOrDefault(bookingId, List.of());
        }
    }
}