package dev.kaiwen.eventpulse.service;

import java.util.List;
import java.util.UUID;

import dev.kaiwen.eventpulse.dto.BookingDtos.RedeemResult;

/**
 * Ticket business surface. Extends {@link TicketIssuer} so the payment
 * confirmation path keeps injecting the narrow issuing capability while
 * reveal/redeem stay on one interface (no duplicated responsibility).
 */
public interface TicketService extends TicketIssuer {

    String REVEAL_PREFIX = "ep:ticket-reveal:";

    java.util.List<String> revealTokens(UUID userId, UUID bookingId);

    RedeemResult redeem(UUID organiserUserId, String rawToken, String rawIdempotencyKey);
}