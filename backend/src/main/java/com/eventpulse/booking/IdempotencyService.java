package com.eventpulse.booking;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.eventpulse.common.AppProperties;
import com.eventpulse.common.CanonicalJson;
import com.eventpulse.common.DbClock;
import com.eventpulse.common.error.ApiException;
import com.eventpulse.common.error.ErrorCode;
import com.eventpulse.outbox.OutboxJson;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Request-fingerprint idempotency. Keys must carry at least 128 bits of
 * entropy; only their HMAC digest is stored. The claim lives inside the
 * caller's transaction, so a business failure rolls the claim back. Concurrent
 * claims wait for the first transaction to commit, then either replay the
 * stored response, fail with 409 on a different fingerprint, or get a 202
 * while the first transaction is still running.
 */
@Service
public class IdempotencyService {

    /** Replay of a completed request: carries the stored response + status. */
    public static final class IdempotentReplay extends RuntimeException {
        public final int statusCode;
        public final Object response;

        public IdempotentReplay(int statusCode, Object response) {
            super("idempotent replay");
            this.statusCode = statusCode;
            this.response = response;
        }
    }

    /** First transaction still in flight. */
    public static final class IdempotencyInProgress extends RuntimeException {
        public IdempotencyInProgress() {
            super("request in progress");
        }
    }

    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final DbClock clock;
    /**
     * Conflict arbitration runs in its own transaction: after a unique
     * violation PostgreSQL aborts the surrounding transaction (25P02), so the
     * committed claim can only be read from a fresh one. The first
     * transaction has already committed by the time the conflicting INSERT
     * returns, so this read observes its final state.
     */
    private final TransactionTemplate requiresNew;

    public IdempotencyService(JdbcTemplate jdbc, AppProperties properties, DbClock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public record Fingerprint(UUID actor, String scope, String keyDigest, String requestHash) {
    }

    public Fingerprint claim(UUID actor, String scope, String rawKey, Object businessRequest) {
        if (rawKey == null || rawKey.length() < 32 || rawKey.length() > 200) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "idempotency key must be a high-entropy value "
                    + "(>= 128 bits)", Map.of("Idempotency-Key", "at least 32 random characters"));
        }
        String keyDigest = CanonicalJson.hmacSha256Hex(properties.security().secretKey(), rawKey);
        String requestHash = CanonicalJson.sha256Hex(
                CanonicalJson.canonicalize(businessRequest, OutboxJson.mapper()));
        try {
            jdbc.update("""
                    INSERT INTO idempotency_records (actor, scope, key_digest, request_hash, state, expires_at)
                    VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?)
                    """, actor, scope, keyDigest, requestHash,
                    java.sql.Timestamp.from(clock.now().plus(Duration.ofHours(24))));
        }
        catch (DuplicateKeyException e) {
            final UUID actorId = actor;
            final String scopeId = scope;
            final String digest = keyDigest;
            final String hash = requestHash;
            requiresNew.execute(status -> {
                resolveConflict(actorId, scopeId, digest, hash);
                return null;
            });
        }
        return new Fingerprint(actor, scope, keyDigest, requestHash);
    }

    public void complete(Fingerprint fingerprint, int statusCode, Object response) {
        jdbc.update("""
                UPDATE idempotency_records SET state = 'COMPLETED', status_code = ?, response = ?::jsonb
                WHERE actor = ? AND scope = ? AND key_digest = ?
                """, statusCode, OutboxJson.write(response), fingerprint.actor(), fingerprint.scope(),
                fingerprint.keyDigest());
    }

    void resolveConflict(UUID actor, String scope, String keyDigest, String requestHash) {
        // The conflicting INSERT waited for the first transaction; at READ
        // COMMITTED this select now sees the committed claim.
        record Row(String requestHash, String state, Integer statusCode, String response, java.time.Instant expiresAt) {
        }
        java.util.List<Row> rows = jdbc.query("""
                SELECT request_hash, state, status_code, response::text AS response, expires_at
                FROM idempotency_records WHERE actor = ? AND scope = ? AND key_digest = ?
                """, (rs, i) -> new Row(rs.getString("request_hash"), rs.getString("state"),
                rs.getObject("status_code", Integer.class), rs.getString("response"),
                rs.getObject("expires_at", java.time.OffsetDateTime.class).toInstant()), actor, scope, keyDigest);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "idempotency claim raced, retry");
        }
        Row row = rows.getFirst();
        if (!requestHash.equals(row.requestHash())) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "idempotency key reused with a different request");
        }
        if ("COMPLETED".equals(row.state)) {
            Object body = row.response() == null ? Map.of() : OutboxJson.mapper().readValue(row.response(),
                    Map.class);
            throw new IdempotentReplay(row.statusCode() == null ? HttpStatus.OK.value() : row.statusCode(), body);
        }
        if (row.expiresAt() != null && row.expiresAt().isBefore(clock.now())) {
            throw new ApiException(ErrorCode.CONFLICT, "idempotency claim expired while in progress");
        }
        throw new IdempotencyInProgress();
    }
}
