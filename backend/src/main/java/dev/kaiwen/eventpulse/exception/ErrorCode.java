package dev.kaiwen.eventpulse.exception;

/**
 * Stable machine-readable error codes. The HTTP status is derived, the code
 * is what clients branch on. Object-existence and authorization failures use
 * the same hidden-object policy (NOT_FOUND) where the plan requires it.
 */
public enum ErrorCode {

    VALIDATION_FAILED(400, "VALIDATION_FAILED"),
    MALFORMED_REQUEST(400, "MALFORMED_REQUEST"),
    UNAUTHENTICATED(401, "UNAUTHENTICATED"),
    INVALID_CREDENTIALS(401, "INVALID_CREDENTIALS"),
    TOKEN_EXPIRED(401, "TOKEN_EXPIRED"),
    TOKEN_REUSE_DETECTED(401, "TOKEN_REUSE_DETECTED"),
    FORBIDDEN(403, "FORBIDDEN"),
    REAUTH_REQUIRED(403, "REAUTH_REQUIRED"),
    NOT_FOUND(404, "NOT_FOUND"),
    METHOD_NOT_ALLOWED(405, "METHOD_NOT_ALLOWED"),
    CONFLICT(409, "CONFLICT"),
    IDEMPOTENCY_KEY_REUSED(409, "IDEMPOTENCY_KEY_REUSED"),
    INSUFFICIENT_INVENTORY(409, "INSUFFICIENT_INVENTORY"),
    INSUFFICIENT_BALANCE(409, "INSUFFICIENT_BALANCE"),
    PER_USER_LIMIT_EXCEEDED(409, "PER_USER_LIMIT_EXCEEDED"),
    SALE_WINDOW_CLOSED(409, "SALE_WINDOW_CLOSED"),
    BOOKING_NOT_PAYABLE(409, "BOOKING_NOT_PAYABLE"),
    BOOKING_NOT_CANCELLABLE(409, "BOOKING_NOT_CANCELLABLE"),
    TICKET_ALREADY_USED(409, "TICKET_NOT_REDEEMABLE"),
    TICKET_NOT_REDEEMABLE(409, "TICKET_NOT_REDEEMABLE"),
    CURSOR_INVALID(400, "CURSOR_INVALID"),
    CURSOR_EXPIRED(400, "CURSOR_EXPIRED"),
    AGE_REQUIREMENT_NOT_CONFIRMED(422, "AGE_REQUIREMENT_NOT_CONFIRMED"),
    RATE_LIMITED(429, "RATE_LIMITED"),
    INTERNAL(500, "INTERNAL"),
    SERVICE_UNAVAILABLE(503, "SERVICE_UNAVAILABLE");

    private final int status;
    private final String code;

    ErrorCode(int status, String code) {
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
