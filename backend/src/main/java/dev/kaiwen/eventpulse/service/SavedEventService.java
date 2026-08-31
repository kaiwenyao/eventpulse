package dev.kaiwen.eventpulse.service;

import java.util.Map;
import java.util.UUID;

/**
 * Saved (favourite) events per user plus the interaction records those
 * actions emit for the recommendation pipeline.
 */
public interface SavedEventService {

    /** Save (favourite) an event; idempotent. */
    void save(UUID userId, UUID eventId);

    /** Unsave an event. */
    void unsave(UUID userId, UUID eventId);

    Map<String, Object> saved(UUID userId);
}