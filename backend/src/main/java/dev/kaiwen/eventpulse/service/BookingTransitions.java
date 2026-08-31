package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.UUID;

import dev.kaiwen.eventpulse.dto.BookingDtos.PaymentIntentView;

/**
 * Protocol-B state transitions contract. Every entry point locks rows in the
 * fixed order booking → quota → inventory → reservation → tickets → payment
 * balance → user_wallet, then re-validates status. Exactly one racing
 * transition wins; losers return without side effects. Wallet debit and
 * credit happen inside these transactions.
 */
public interface BookingTransitions {

    /** Spring application event raised after every successful transition. */
    record BookingStatusChanged(UUID bookingId, String status, String refundState) {
    }

    record BookingRow(UUID id, UUID userId, UUID eventId, UUID tierId, Integer quantity, String status,
                      String entitlementStatus, String refundState, Long unitPriceMinor, String currency,
                      String policySnapshot, UUID activeIntentId, Instant expiresAt, Integer version) {
    }

    /**
     * Single-flight payment: debit the user wallet and confirm the booking in
     * one transaction. A second request after success is not payable.
     */
    PaymentIntentView createPaymentIntent(UUID bookingId);

    boolean cancel(UUID actorId, UUID bookingId, boolean eventCancelled, String actorKind);

    boolean expireBooking(UUID bookingId);
}