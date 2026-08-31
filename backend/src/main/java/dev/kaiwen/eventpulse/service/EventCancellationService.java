package dev.kaiwen.eventpulse.service;

import java.util.UUID;

import dev.kaiwen.eventpulse.security.AuthUser;

/**
 * Orchestrates event cancellation without extending the event-row transaction
 * across the booking cancellation batch.
 */
public interface EventCancellationService {

    record CancellationResult(int cancelled, int failed) {
    }

    CancellationResult cancelEvent(AuthUser user, UUID eventId, String reason);
}
