package dev.kaiwen.eventpulse.service;

import java.util.UUID;

/**
 * Recommendation V1 embedding support contract. A deterministic feature-hash
 * embedder (token -> 64-dim unit vector) runs fully offline. When pgvector is
 * present the column is used; when it is absent the implementation degrades
 * silently to V0. Embeddings never influence transactions, inventory or
 * authorization.
 */
public interface EmbeddingService {

    int DIMENSIONS = 64;

    void embedEvent(UUID eventId, String title, String category, String description);

    boolean isVectorAvailable();

    double[] embed(String text);
}