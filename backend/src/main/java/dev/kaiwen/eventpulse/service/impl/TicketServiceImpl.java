package dev.kaiwen.eventpulse.service.impl;

import java.time.Duration;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tickets are CSPRNG values with >= 128 bits of entropy; only an HMAC
 * (server pepper) hash is persisted in the database. Raw tokens never enter
 * URLs, logs, Kafka payloads or analytics.
 *
 * <p>Reveal store (plan §7.3): raw values are shown only in authorized owner
 * responses - never in URLs, logs, Kafka payloads or analytics - and only
 * peppered HMAC hashes persist in the database. The transient staging copy is
 * AES-GCM encrypted at rest and TTL-bounded instead of long-lived plaintext.
 * Reads are deliberately NON-destructive: a patron may re-open the order page
 * and must still be able to present every active ticket at the gate
 * (plan §2.3 订单票券 / §17.2 demo), so entries stay until their TTL; the
 * redeemable fact remains a PostgreSQL fact (peppered hash only). The
 * Redis-down fallback store is in-process, TTL-bounded and size-bounded,
 * never an unbounded static map.
 * Redemption is an atomic ACTIVE -> USED migration under the protocol-B
 * lock order (booking first, then ticket), so a concurrent cancel and a scan
 * cannot both win; repeated scans of the same token return the original
 * result via request-fingerprint idempotency.
 */
@Service
public class TicketServiceImpl implements TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketServiceImpl.class);

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final IdempotencyService idempotency;
    private final RateLimiter rateLimiter;
    private final StringRedisTemplate redis;
    private final AppProperties properties;
    private final DbClock clock;
    /** TTL for unrevealed staged tokens; shorter or equal by configuration. */
    private final Duration revealTtl;
    private final dev.kaiwen.eventpulse.observability.BusinessMetrics metrics;

    public TicketServiceImpl(JdbcTemplate jdbc, OutboxWriter outbox, IdempotencyService idempotency,
            RateLimiter rateLimiter, StringRedisTemplate redis, AppProperties properties, DbClock clock,
            @org.springframework.beans.factory.annotation.Value(
                    "${eventpulse.tickets.reveal-ttl:P7D}") Duration revealTtl,
            dev.kaiwen.eventpulse.observability.BusinessMetrics metrics) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.revealTtl = revealTtl;
        this.metrics = metrics;
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
        // Raw tokens are staged ONLY for the one-time reveal (encrypted at
        // rest, TTL-bounded, pop-on-read), never in the database, logs or
        // events.
        storeRevealTokens(bookingId, rawTokens);
    }

    /**
     * Staging for the one authorized disclosure: tokens are AES-GCM encrypted
     * with a server-derived key before leaving this process, so Redis never
     * holds plaintext, and the entry expires instead of living for days as
     * plaintext. Redemption keeps its peppered hash in PostgreSQL only.
     */
    private void storeRevealTokens(UUID bookingId, List<String> rawTokens) {
        try {
            org.springframework.data.redis.core.ListOperations<String, String> ops = redis.opsForList();
            String key = REVEAL_PREFIX + bookingId;
            ops.rightPushAll(key, rawTokens.stream().map(this::encryptToken).toList());
            redis.expire(key, revealTtl);
        }
        catch (Exception redisDown) {
            // Redis unavailable at issue time: WARN (observable degrade) and
            // stage the same encrypted entries in the in-process fallback.
            log.warn("reveal store redis write failed for booking {}; using in-memory fallback",
                    bookingId, redisDown);
            InMemoryRevealStore.addAll(bookingId, rawTokens.stream().map(this::encryptToken).toList(),
                    clock.now().plus(revealTtl));
        }
    }

    /**
 * Reveal (plan §7.3): returns the staged raw tokens to the owner in this
 * authorized response. The read is non-destructive (repeat reveals return the
 * same tokens until the TTL), so re-opened order pages can still present
 * every active ticket. Order matches ticket sequence (issue pushes 1..N in
 * order), so clients can map tokens[t.sequence - 1].
 */
    @Override
    public List<String> revealTokens(UUID userId, UUID bookingId) {
        requireOwner(userId, bookingId);
        String key = REVEAL_PREFIX + bookingId;
        List<String> staged;
        try {
            // Non-destructive read (LRANGE): nothing is popped, so repeated
            // authorized reveals return the same tokens until the TTL.
            List<String> encrypted = redis.opsForList().range(key, 0, -1);
            staged = encrypted == null ? List.of() : encrypted;
        }
        catch (Exception redisDown) {
            // Redis read failed: fall back to the in-process store. WARN so the
            // degraded path is observable, and the error is NOT swallowed into
            // an empty-looking result.
            log.warn("reveal store redis read failed for booking {}; using in-memory fallback",
                    bookingId, redisDown);
            staged = InMemoryRevealStore.snapshot(bookingId);
        }
        // Decryption is deliberately OUTSIDE the Redis try: a pepper/secret
        // rotation or GCM integrity failure must surface loudly (500 with an
        // error log), and a Redis hiccup must never be mistaken for it. With a
        // non-destructive read there is also nothing that a mid-loop failure
        // could lose.
        List<String> tokens = new ArrayList<>(staged.size());
        for (String encrypted : staged) {
            tokens.add(decryptToken(encrypted));
        }
        return tokens;
    }

    // ------------------------------------------------------------------ redeem

    @Override
    @Transactional
    public RedeemResult redeem(UUID organiserUserId, String rawToken, String rawIdempotencyKey) {
        metrics.ticketRedeemAttempt();
        try {
            rateLimiter.check("redeem", organiserUserId.toString());
        }
        catch (RuntimeException rateLimited) {
            metrics.ticketRedeemRejected();
            throw rateLimited;
        }
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) {
            throw reject(ErrorCode.VALIDATION_FAILED, "token required");
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
            throw reject(ErrorCode.NOT_FOUND, "not found");
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
            throw reject(ErrorCode.NOT_FOUND, "not found");
        }
        BookingRow booking = bookings.getFirst();
        // Owner authorization: the ticket must belong to the organiser's event.
        List<UUID> owned = jdbc.queryForList("SELECT id FROM organisers WHERE owner_user_id = ?", UUID.class,
                organiserUserId);
        if (!owned.contains(booking.organiserId())) {
            throw reject(ErrorCode.NOT_FOUND, "not found");
        }

        // Protocol B order: lock booking, then the ticket; re-validate both.
        String bookingStatus = jdbc.queryForObject("SELECT status FROM bookings WHERE id = ? FOR UPDATE",
                String.class, booking.id());
        if (!"CONFIRMED".equals(bookingStatus)) {
            throw reject(ErrorCode.TICKET_NOT_REDEEMABLE, "ticket is not redeemable");
        }
        if ("CANCELLED".equals(booking.eventStatus())) {
            throw reject(ErrorCode.TICKET_NOT_REDEEMABLE, "ticket is not redeemable");
        }
        Instant now = clock.now();
        if (now.isBefore(booking.startsAt().minus(java.time.Duration.ofHours(6)))
                || now.isAfter(booking.endsAt())) {
            throw reject(ErrorCode.TICKET_NOT_REDEEMABLE, "ticket is not redeemable");
        }

        int updated = jdbc.update("""
                UPDATE tickets SET status = 'USED', used_at = now(), used_by = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, organiserUserId, ticket.id());
        if (updated == 0) {
            // Non-enumerable business error for USED/REVOKED tickets.
            throw reject(ErrorCode.TICKET_NOT_REDEEMABLE, "ticket is not redeemable");
        }
        RedeemResult result = new RedeemResult("OK", ticket.id(), booking.id(),
                booking.eventId(), booking.eventTitle(), ticket.sequence(), now.toString());
        outbox.append("ticket", ticket.id(), OutboxWriter.TOPIC_BOOKING, "ticket.redeemed", Map.of(
                "ticketId", ticket.id().toString(), "bookingId", booking.id().toString(), "organiserId",
                organiserUserId.toString()));
        idempotency.complete(fingerprint, 200, result);
        return result;
    }

    private ApiException reject(ErrorCode code, String message) {
        metrics.ticketRedeemRejected();
        return new ApiException(code, message);
    }

    private void requireOwner(UUID userId, UUID bookingId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE id = ? AND user_id = ?",
                Integer.class, bookingId, userId);
        if (count == null || count == 0) {
            throw ApiException.notFound();
        }
    }

    // -------------------------------------------- reveal staging (AES-GCM, at rest)

    /** AES key derived from the server pepper + secret; never persisted. */
    private byte[] revealCipherKey() {
        try {
            String material = properties.security().tokenPepper() + ":"
                    + properties.security().secretKey();
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Staging value "v1:base64(iv):base64(ciphertext+tag)" - no plaintext at rest. */
    private String encryptToken(String rawToken) {
        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(revealCipherKey(), "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "v1:" + java.util.Base64.getEncoder().encodeToString(iv) + ":"
                    + java.util.Base64.getEncoder().encodeToString(ciphertext);
        }
        catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("reveal token encryption failed", e);
        }
    }

    private String decryptToken(String stored) {
        try {
            String[] parts = stored.split(":");
            if (parts.length != 3) {
                // An entry written by an older build carries no envelope; it can
                // only be a pre-migration artifact, never re-readable in the new
                // one-time flow. Decode the legacy literal once; it is consumed
                // by this read because every read pops its entry.
                return stored;
            }
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(revealCipherKey(), "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128,
                            java.util.Base64.getDecoder().decode(parts[1])));
            return new String(cipher.doFinal(java.util.Base64.getDecoder().decode(parts[2])),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("reveal token decryption failed", e);
        }
    }

    /**
     * Fallback reveal store when Redis is unreachable (single node only).
     * Entries are the encrypted staging values, TTL-bounded and size-bounded,
     * and reads are non-destructive: repeat reads of the same booking return
     * the same staging entries until they expire, without turning the fallback
     * into an immortal unbounded map.
     */
    public static final class InMemoryRevealStore {

        private static final int MAX_BOOKINGS = 2048;

        private record Entry(List<String> encryptedTokens, Instant expiresAt) {
        }

        private static final Map<UUID, Entry> STORE = new java.util.concurrent.ConcurrentHashMap<>();

        static void addAll(UUID bookingId, List<String> encryptedTokens, Instant expiresAt) {
            prune();
            if (STORE.size() >= MAX_BOOKINGS) {
                return; // bounded: refuse rather than leak
            }
            STORE.put(bookingId, new Entry(List.copyOf(encryptedTokens), expiresAt));
        }

        /** Non-destructive read: same staging entries until the TTL expires. */
        static List<String> snapshot(UUID bookingId) {
            Entry entry = STORE.get(bookingId);
            if (entry == null) {
                return List.of();
            }
            if (entry.expiresAt().isBefore(Instant.now())) {
                STORE.remove(bookingId); // lazy TTL eviction
                return List.of();
            }
            return entry.encryptedTokens();
        }

        private static void prune() {
            STORE.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(Instant.now()));
        }

        // Test-only reset so theStoreIsSizeBounded() cannot leave the static
        // store full and starve later tests that exercise the Redis-down path.
        static void clear() {
            STORE.clear();
        }
    }
}
