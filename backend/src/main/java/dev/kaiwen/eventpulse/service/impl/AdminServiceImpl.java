package dev.kaiwen.eventpulse.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.common.web.RateLimiter;
import dev.kaiwen.eventpulse.common.web.TraceIdFilter;
import dev.kaiwen.eventpulse.exception.ApiException;
import dev.kaiwen.eventpulse.exception.ErrorCode;
import dev.kaiwen.eventpulse.outbox.OutboxJson;
import dev.kaiwen.eventpulse.security.AuthUser;
import dev.kaiwen.eventpulse.service.AdminService;
import dev.kaiwen.eventpulse.service.AdminService.AbandonRefundRequest;
import dev.kaiwen.eventpulse.service.AdminService.ReauthRequest;
import dev.kaiwen.eventpulse.service.AdminService.ResolveGapRequest;
import dev.kaiwen.eventpulse.service.AdminService.RetryCommandRequest;
import dev.kaiwen.eventpulse.service.AdminService.ReplayRequest;
import dev.kaiwen.eventpulse.service.AuthService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin recovery operations. Re-auth token issuance, exception overview,
 * command retry, consumer-gap resolution, outbox replay and the human refund
 * waiver; masking of credential-ish columns keeps the admin view safe for
 * triage while audit rows stay append-only.
 */
@Service
public class AdminServiceImpl implements AdminService {

    private final JdbcTemplate jdbc;
    private final AuthService authService;
    private final AppProperties properties;
    private final RateLimiter rateLimiter;

    public AdminServiceImpl(JdbcTemplate jdbc, AuthService authService, AppProperties properties,
            RateLimiter rateLimiter) {
        this.jdbc = jdbc;
        this.authService = authService;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Map<String, Object> reauth(AuthUser user, ReauthRequest request) {
        rateLimiter.check("reauth", user.id().toString());
        boolean ok = authService.verifyPassword(user.id(), request.password());
        if (!ok) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "re-authentication failed");
        }
        String raw = CanonicalJson.newOpaqueToken();
        Duration ttl = properties.security().adminReauthTtl();
        jdbc.update("""
                INSERT INTO admin_reauth_tokens (token_hash, user_id, expires_at) VALUES (?, ?, ?)
                """, CanonicalJson.sha256Hex(raw), user.id(),
                java.sql.Timestamp.from(Instant.now().plus(ttl)));
        return Map.of("reauthToken", raw, "expiresInSeconds", ttl.toSeconds());
    }

    @Override
    public Map<String, Object> exceptions(AuthUser user, String reauthToken) {
        requireFreshReauth(user, reauthToken);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("manualReviewCommands", maskRows(jdbc.queryForList("""
                SELECT id, kind, aggregate_id, provider_key, attempts, last_error, updated_at
                FROM commands WHERE state = 'MANUAL_REVIEW' ORDER BY updated_at DESC LIMIT 100
                """)));
        out.put("unknownCommands", maskRows(jdbc.queryForList("""
                SELECT id, kind, aggregate_id, provider_key, attempts, next_attempt_at
                FROM commands WHERE state = 'UNKNOWN_QUERY' ORDER BY next_attempt_at LIMIT 100
                """)));
        out.put("failedRefunds", jdbc.queryForList("""
                SELECT r.id, r.booking_id, r.amount_minor, r.state, r.updated_at
                FROM refunds r WHERE r.state IN ('FAILED', 'MANUAL_REVIEW') ORDER BY r.updated_at DESC LIMIT 100
                """));
        out.put("unknownPayments", maskRows(jdbc.queryForList("""
                SELECT id, booking_id, provider_key, state, updated_at FROM payment_intents
                WHERE state = 'UNKNOWN' OR (state = 'CAPTURE_SUBMITTED' AND updated_at < now() - interval '1 hour')
                LIMIT 100
                """)));
        out.put("openConsumerGaps", jdbc.queryForList("""
                SELECT id, consumer, aggregate_type, aggregate_id, expected, received, created_at
                FROM consumer_gaps WHERE state = 'OPEN' ORDER BY created_at DESC LIMIT 100
                """));
        out.put("outboxOldestPendingSeconds", jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM max(now() - created_at)), 0) FROM outbox
                WHERE state = 'PENDING'
                """, Double.class));
        out.put("commandsRunningLeases", maskRows(jdbc.queryForList("""
                SELECT id, kind, lease_owner, lease_until FROM commands
                WHERE state = 'RUNNING' ORDER BY lease_until LIMIT 50
                """)));
        return out;
    }

    @Override
    public Map<String, Object> retryCommand(AuthUser user, UUID id, String reauthToken,
            RetryCommandRequest request) {
        requireFreshReauth(user, reauthToken);
        int updated = jdbc.update("""
                UPDATE commands SET state = 'READY', next_attempt_at = now(), last_error = NULL,
                       lease_owner = NULL, lease_until = NULL, updated_at = now()
                WHERE id = ? AND state = 'MANUAL_REVIEW'
                """, id);
        audit(user, "admin.command.retry", "command", id.toString(), null,
                Map.of("reason", request == null || request.reason() == null ? "" : request.reason()));
        if (updated != 1) {
            throw new ApiException(ErrorCode.NOT_FOUND, "command not in manual review");
        }
        return Map.of("retried", true);
    }

    @Override
    @Transactional
    public Map<String, Object> resolveGap(AuthUser user, UUID id, String reauthToken, ResolveGapRequest request) {
        requireFreshReauth(user, reauthToken);
        if (request == null || request.strategy() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "strategy required");
        }
        record Gap(String consumer, String aggregateType, UUID aggregateId, long expected, long received,
                   String state) {
        }
        List<Gap> gaps = jdbc.query("SELECT consumer, aggregate_type, aggregate_id, expected, received, state "
                + "FROM consumer_gaps WHERE id = ? FOR UPDATE", (rs, i) -> new Gap(rs.getString("consumer"),
                rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                rs.getLong("expected"), rs.getLong("received"), rs.getString("state")), id);
        if (gaps.isEmpty() || !"OPEN".equals(gaps.getFirst().state())) {
            throw ApiException.notFound();
        }
        Gap gap = gaps.getFirst();
        if (Boolean.TRUE.equals(request.dryRun())) {
            return Map.of("dryRun", true, "strategy", request.strategy(), "would",
                    planFor(gap.expected(), gap.received()));
        }
        switch (request.strategy()) {
            case "REPLAY" -> {
                jdbc.update("""
                        UPDATE outbox SET state = 'PENDING', published_at = NULL
                        WHERE aggregate_type = ? AND aggregate_id = ? AND sequence >= ?
                        """, gap.aggregateType(), gap.aggregateId(), gap.expected());
                jdbc.update("UPDATE consumer_gaps SET state = 'RESOLVED_REPLAY', resolution_note = ?, "
                        + "approved_by = ?, resolved_at = now() WHERE id = ?", request.note(),
                        actorName(user), id);
            }
            case "REBUILD_CURSOR" -> {
                Long maxSeq = jdbc.queryForObject("""
                        SELECT COALESCE(MAX(sequence), 0) FROM outbox
                        WHERE aggregate_type = ? AND aggregate_id = ? AND state = 'PUBLISHED'
                        """, Long.class, gap.aggregateType(), gap.aggregateId());
                jdbc.update("""
                        UPDATE consumer_cursors SET last_sequence = ?, updated_at = now()
                        WHERE consumer = ? AND aggregate_type = ? AND aggregate_id = ?
                        """, maxSeq, gap.consumer(), gap.aggregateType(), gap.aggregateId());
                jdbc.update("UPDATE consumer_gaps SET state = 'RESOLVED_REBUILD', resolution_note = ?, "
                        + "approved_by = ?, resolved_at = now() WHERE id = ?", request.note(),
                        actorName(user), id);
            }
            case "SKIP" -> {
                if (request.approvedBy() == null || request.approvedBy().isBlank()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "SKIP requires a second-person approval record");
                }
                jdbc.update("""
                        UPDATE consumer_cursors SET last_sequence = ?, updated_at = now()
                        WHERE consumer = ? AND aggregate_type = ? AND aggregate_id = ?
                        """, gap.received(), gap.consumer(), gap.aggregateType(), gap.aggregateId());
                jdbc.update("UPDATE consumer_gaps SET state = 'RESOLVED_SKIP', resolution_note = ?, "
                        + "approved_by = ?, resolved_at = now() WHERE id = ?", request.note(),
                        request.approvedBy(), id);
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown strategy");
        }
        audit(user, "admin.consumer_gap." + request.strategy().toLowerCase(), "consumer_gap", id.toString(),
                Map.of("expected", gap.expected(), "received", gap.received()),
                Map.of("note", request.note() == null ? "" : request.note(), "approvedBy",
                        request.approvedBy() == null ? "" : request.approvedBy()));
        return Map.of("resolved", true, "strategy", request.strategy());
    }

    @Override
    public Map<String, Object> replayOutbox(AuthUser user, String reauthToken, ReplayRequest request) {
        requireFreshReauth(user, reauthToken);
        List<Object> params = new ArrayList<>();
        String where = "";
        if (request.aggregateType() != null) {
            where += " AND aggregate_type = ?";
            params.add(request.aggregateType());
            if (request.aggregateId() != null) {
                where += " AND aggregate_id = ?";
                params.add(UUID.fromString(request.aggregateId()));
            }
        }
        if (Boolean.TRUE.equals(request.dryRun())) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE state = 'PUBLISHED'" + where,
                    Long.class, params.toArray());
            return Map.of("dryRun", true, "wouldReplay", count == null ? 0 : count);
        }
        int updated = jdbc.update("""
                UPDATE outbox SET state = 'PENDING', published_at = NULL WHERE state = 'PUBLISHED'
                """ + where, params.toArray());
        audit(user, "admin.outbox.replay", "outbox", request.aggregateType() == null ? "*"
                : request.aggregateId(), null, Map.of("replayed", updated));
        return Map.of("replayed", updated);
    }

    @Override
    @Transactional
    public Map<String, Object> abandonRefund(AuthUser user, UUID id, String reauthToken,
            AbandonRefundRequest request) {
        requireFreshReauth(user, reauthToken);
        record Ref(UUID bookingId, Long amount, String state) {
        }
        List<Ref> refs = jdbc.query("""
                SELECT booking_id, amount_minor, state FROM refunds WHERE id = ? FOR UPDATE
                """, (rs, i) -> new Ref(rs.getObject("booking_id", UUID.class),
                rs.getObject("amount_minor", Long.class), rs.getString("state")), id);
        if (refs.isEmpty()) {
            throw ApiException.notFound();
        }
        Ref refund = refs.getFirst();
        if (!List.of("FAILED", "MANUAL_REVIEW").contains(refund.state())) {
            throw new ApiException(ErrorCode.CONFLICT, "refund is not in a failed/manual state");
        }
        jdbc.update("""
                UPDATE payment_balance SET refund_reserved_amount_minor = refund_reserved_amount_minor - ?,
                       version = version + 1, updated_at = now()
                WHERE booking_id = ? AND refund_reserved_amount_minor >= ?
                """, refund.amount(), refund.bookingId(), refund.amount());
        jdbc.update("UPDATE refunds SET state = 'ABANDONED', updated_at = now() WHERE id = ?", id);
        jdbc.update("UPDATE bookings SET refund_state = 'MANUAL_REVIEW', updated_at = now() WHERE id = ?",
                refund.bookingId());
        audit(user, "admin.refund.abandon", "refund", id.toString(), Map.of("state", refund.state()),
                Map.of("amount", refund.amount(), "reason", request == null || request.reason() == null ? ""
                        : request.reason()));
        return Map.of("abandoned", true);
    }

    // ----------------------------------------------------------------- helpers

    private void requireFreshReauth(AuthUser user, String reauthToken) {
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "admin role required");
        }
        if (reauthToken == null || reauthToken.isBlank()) {
            throw new ApiException(ErrorCode.REAUTH_REQUIRED, "fresh re-authentication required");
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_reauth_tokens
                WHERE token_hash = ? AND user_id = ? AND expires_at > now()
                """, Integer.class, CanonicalJson.sha256Hex(reauthToken), user.id());
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.REAUTH_REQUIRED, "re-authentication expired, redo it");
        }
    }

    private void audit(AuthUser user, String action, String resourceType, String resourceId,
            Map<String, Object> before, Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO audit_log (actor, action, resource_type, resource_id, before_state, after_state,
                                       trace_id)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """, actorName(user), action, resourceType, resourceId,
                before == null ? null : dev.kaiwen.eventpulse.outbox.OutboxJson.write(before),
                after == null ? null : dev.kaiwen.eventpulse.outbox.OutboxJson.write(after),
                TraceIdFilter.currentTraceId());
    }

    private String actorName(AuthUser user) {
        return user.email();
    }

    /**
     * Mask free-text/credential-ish columns in exception rows (provider keys,
     * error text, lease owners) so the admin view never leaks full values while
     * leaving identifiers, amounts, states and timestamps intact for triage.
     */
    private List<Map<String, Object>> maskRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> masked = new LinkedHashMap<>(row);
            // Mask any non-empty sensitive value (even short ones) so the full
            // value never reaches the admin view. A fixed 4-char prefix plus the
            // marker keeps enough for triage without leaking the remainder.
            for (String col : new String[] {"provider_key", "last_error", "lease_owner"}) {
                if (masked.get(col) instanceof String s && !s.isBlank()) {
                    String prefix = s.length() <= 4 ? s : s.substring(0, 4);
                    masked.put(col, prefix + "…");
                }
            }
            out.add(masked);
        }
        return out;
    }

    private String planFor(long expected, long received) {
        List<String> plan = new ArrayList<>();
        plan.add("strategy REPLAY re-marks outbox sequences >= " + expected + " for redelivery");
        plan.add("strategy REBUILD_CURSOR sets the cursor to the highest published sequence");
        plan.add("strategy SKIP advances the cursor to " + received + " and requires an approval record");
        return String.join("; ", plan);
    }
}