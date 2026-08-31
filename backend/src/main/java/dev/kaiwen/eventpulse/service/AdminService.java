package dev.kaiwen.eventpulse.service;

import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.security.AuthUser;

/**
 * Admin exception surface. Every high-risk action requires ADMIN plus a fresh
 * re-authentication; recovery actions support dry-run, are idempotent and
 * append to the audit log.
 */
public interface AdminService {

    public record ReauthRequest(String password) {
    }

    public record ResolveGapRequest(String strategy, String note, String approvedBy, Boolean dryRun) {
    }

    public record RetryCommandRequest(String reason) {
    }

    public record AbandonRefundRequest(String reason) {
    }

    public record ReplayRequest(String aggregateType, String aggregateId, Boolean dryRun) {
    }

    /** Fresh re-authentication for admin actions (MFA freshness window). */
    Map<String, Object> reauth(AuthUser user, ReauthRequest request);

    /** Exception overview: manual review, UNKNOWN, refund failures, gaps, DLT. */
    Map<String, Object> exceptions(AuthUser user, String reauthToken);

    /** Retry a manual-review command with its original providerKey. */
    Map<String, Object> retryCommand(AuthUser user, UUID id, String reauthToken, RetryCommandRequest request);

    /** Resolve a consumer gap: REPLAY, REBUILD_CURSOR or audited SKIP. */
    Map<String, Object> resolveGap(AuthUser user, UUID id, String reauthToken, ResolveGapRequest request);

    /** Re-deliver published outbox events (idempotent consumers dedupe). */
    Map<String, Object> replayOutbox(AuthUser user, String reauthToken, ReplayRequest request);

    /** Human waiver: release a refund reservation after explicit approval. */
    Map<String, Object> abandonRefund(AuthUser user, UUID id, String reauthToken, AbandonRefundRequest request);
}