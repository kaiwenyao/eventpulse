package dev.kaiwen.eventpulse.service.impl;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.kaiwen.eventpulse.service.EmbeddingService;

/**
 * Recommendation V1 embedding support. A deterministic feature-hash embedder
 * (token -> 64-dim unit vector) runs fully offline, so the vector column is
 * populated without any remote model or code execution. When pgvector is
 * present the column is used; when it is absent the service degrades
 * silently to V0. Embeddings never influence transactions, inventory or
 * authorization.
 */
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private final JdbcTemplate jdbc;
    private final boolean vectorAvailable;

    public EmbeddingServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.vectorAvailable = vectorColumnPresent();
    }

    @Override
    public void embedEvent(UUID eventId, String title, String category, String description) {
        if (!vectorAvailable) {
            return;
        }
        String text = (title + " " + category + " " + (description == null ? "" : description)).toLowerCase();
        double[] vector = embed(text);
        jdbc.update("UPDATE events SET embedding = ?::vector WHERE id = ?", toPgVectorLiteral(vector), eventId);
    }

    @Override
    public boolean isVectorAvailable() {
        return vectorAvailable;
    }

    @Override
    public double[] embed(String text) {
        double[] out = new double[DIMENSIONS];
        for (String token : text.split("[^a-z0-9\\p{IsHan}]+")) {
            if (token.isBlank()) {
                continue;
            }
            int index = Math.floorMod(token.hashCode(), DIMENSIONS);
            out[index] += 1.0;
        }
        double norm = 0;
        for (double v : out) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < DIMENSIONS; i++) {
                out[i] /= norm;
            }
        }
        return out;
    }

    private String toPgVectorLiteral(double[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(String.format("%.6f", vector[i]));
        }
        return sb.append("]").toString();
    }

    private boolean vectorColumnPresent() {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_name = 'events' AND column_name = 'embedding'
                    """, Integer.class);
            return count != null && count > 0;
        }
        catch (Exception e) {
            return false;
        }
    }
}