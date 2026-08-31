package dev.kaiwen.eventpulse.service;

import java.util.UUID;

/**
 * Request-fingerprint idempotency contract. Keys must carry at least 128 bits
 * of entropy; only their HMAC digest is stored. The claim lives inside the
 * caller's transaction, so a business failure rolls the claim back.
 */
public interface IdempotencyService {

    public record Fingerprint(UUID actor, String scope, String keyDigest, String requestHash) {
    }

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

    Fingerprint claim(UUID actor, String scope, String rawKey, Object businessRequest);

    void complete(Fingerprint fingerprint, int statusCode, Object response);
}