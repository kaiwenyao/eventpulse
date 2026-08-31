package com.eventpulse.ticketing;

import java.util.UUID;

/**
 * Issued by the payment confirmation path inside the same transaction that
 * confirms a booking. Implementations must generate CSPRNG tokens and store
 * only peppered hashes.
 */
public interface TicketIssuer {

    void issue(UUID bookingId, UUID eventId, UUID userId, int quantity);
}
