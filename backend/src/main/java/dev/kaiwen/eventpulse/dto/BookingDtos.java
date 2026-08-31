package dev.kaiwen.eventpulse.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BookingDtos {

    public record CreateBookingRequest(UUID eventId, UUID tierId, Integer quantity, Boolean ageConfirmed) {
    }

    public record CancelRequest(String reason) {
    }

    public record BookingView(UUID id, UUID eventId, UUID tierId, String tierName, Integer quantity,
                              String status, String entitlementStatus, String refundState,
                              Long unitPriceMinor, String currency, Long totalMinor,
                              Map<String, Object> priceSnapshot, Map<String, Object> policySnapshot,
                              String expiresAt, String confirmedAt, String cancelledAt,
                              PaymentIntentView activeIntent, List<RefundView> refunds, List<TicketView> tickets) {
    }

    public record PaymentIntentView(UUID id, Integer attemptNo, String state, Long requestedAmountMinor,
                                    Long capturedAmountMinor, String currency, String providerKey, boolean active) {
    }

    public record RefundView(UUID id, Long amountMinor, String state, String createdAt) {
    }

    /** The raw ticket token never appears here; reveal is a separate one-time call. */
    public record TicketView(UUID id, Integer sequence, String status, String usedAt) {
    }

    public record RedeemRequest(String token) {
    }

    public record RedeemResult(String result, UUID ticketId, UUID bookingId, UUID eventId, String eventTitle,
                               Integer sequence, String usedAt) {
    }

    public record BatchResult(int cancelled, int failed) {
    }

    public record ViewRow(UUID id, UUID eventId, UUID tierId, String tierName, Integer quantity, String status,
                          String entitlementStatus, String refundState, Long unitPriceMinor, String currency,
                          String priceSnapshot, String policySnapshot, String expiresAt, String confirmedAt,
                          String cancelledAt) {
    }

    public record IntentRow(UUID id, Integer attemptNo, String state, Long requestedAmountMinor,
                            Long capturedAmountMinor, String currency, String providerKey, Boolean active) {
    }

    private BookingDtos() {
    }
}
