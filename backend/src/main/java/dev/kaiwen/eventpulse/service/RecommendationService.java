package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Recommendation V0/V1 contract (V0: popularity + hard filters) with a frozen
 * candidate set; page cursors only walk that frozen result. Interaction
 * batches are recorded outbox-first for the recommendation pipeline.
 */
public interface RecommendationService {

    String MODEL_V0 = "v0-popularity";
    String MODEL_V1 = "v1-embedding";
    String FEATURE_VERSION = "fv-2026-08";

    public record RecommendationItem(UUID eventId, String title, String category, Instant startsAt,
                                     String city, Double score, List<String> reasonCodes) {
    }

    public record RecommendationPage(String requestId, String modelVersion, String featureVersion,
                                     Instant queryAsOf, List<RecommendationItem> items, String nextCursor) {
    }

    public record InteractionInput(UUID eventId, String type, Integer position, Instant occurredAt) {
    }

    public record InteractionBatch(String requestId, String sessionId, List<InteractionInput> events) {
    }

    RecommendationPage recommend(UUID userId, String section, Integer limit, String cursor);

    /**
     * Batch interactions; server receive time is the fact, batch capped and
     * deduped. Rate limited per user or anonymous session. Returns the number
     * of newly accepted interactions.
     */
    int recordInteractions(UUID userId, String sessionId, InteractionBatch batch);
}