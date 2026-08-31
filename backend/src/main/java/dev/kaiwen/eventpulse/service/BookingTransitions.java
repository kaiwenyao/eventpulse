package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.UUID;

import dev.kaiwen.eventpulse.dto.BookingDtos.PaymentIntentView;

/**
 * Protocol-B state transitions contract. Every entry point locks rows in the
 * fixed order booking → quota → inventory → reservation → tickets → payment
 * rows, then re-validates status. Exactly one racing transition wins; losers
 * return without side effects. No external gateway call ever happens inside
 * these transactions - the dispatcher owns that boundary.
 */
public interface BookingTransitions {

    /** Spring application event raised after every successful transition. */
    record BookingStatusChanged(UUID bookingId, String status, String refundState) {
    }

    record BookingRow(UUID id, UUID userId, UUID eventId, UUID tierId, Integer quantity, String status,
                      String entitlementStatus, String refundState, Long unitPriceMinor, String currency,
                      String policySnapshot, UUID activeIntentId, Instant expiresAt, Integer version) {
    }

    /** Single-flight payment intent under a partial unique index. */
    PaymentIntentView createPaymentIntent(UUID bookingId);

    boolean cancel(UUID actorId, UUID bookingId, boolean eventCancelled, String actorKind);

    boolean expireBooking(UUID bookingId);

    String completeCapture(UUID bookingId, String captureProviderKey, long amountMinor, String currency,
            String gatewayRef);

    String failCapture(UUID bookingId, String captureProviderKey);

    String completeVoid(UUID bookingId, String voidProviderKey, String captureProviderKey, String voidOutcome);

    /** VOID hit an already-captured charge: convert to a refund compensation. */
    String convertVoidToRefund(UUID bookingId, String captureProviderKey, long amountMinor, String currency);

    void refundSucceeded(UUID refundId, long amountMinor, String providerRef);

    void refundFailed(UUID refundId, boolean manualReview);
}