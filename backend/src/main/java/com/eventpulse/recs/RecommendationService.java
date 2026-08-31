package com.eventpulse.recs;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eventpulse.catalogue.CursorCodec;
import com.eventpulse.catalogue.SearchCursor;
import com.eventpulse.common.DbClock;
import com.eventpulse.common.error.ApiException;
import com.eventpulse.common.error.ErrorCode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recommendation V0 (popularity + hard filters) with a frozen candidate set:
 * each request stores its ordered candidate ids, model/feature versions and
 * queryAsOf for 15 minutes; page cursors only walk that frozen result.
 * Display re-filters cancelled/ended/hidden events so stale stock is never
 * presented as fact. V1 adds text-embedding similarity when pgvector is
 * available (see EmbeddingService); it never gates transactions.
 */
@Service
public class RecommendationService {

    public static final String MODEL_V0 = "v0-popularity";
    public static final String MODEL_V1 = "v1-embedding";
    public static final String FEATURE_VERSION = "fv-2026-08";

    private final JdbcTemplate jdbc;
    private final CursorCodec cursorCodec;
    private final EmbeddingService embeddingService;
    private final DbClock clock;

    public RecommendationService(JdbcTemplate jdbc, CursorCodec cursorCodec, EmbeddingService embeddingService,
            DbClock clock) {
        this.jdbc = jdbc;
        this.cursorCodec = cursorCodec;
        this.embeddingService = embeddingService;
        this.cursorCodecForRecs();
        this.clock = clock;
    }

    private void cursorCodecForRecs() {
        // CursorCodec is shared with search; rec cursors use the same signing.
    }

    public record RecommendationItem(UUID eventId, String title, String category, Instant startsAt,
                                     String city, Double score, List<String> reasonCodes) {
    }

    public record RecommendationPage(String requestId, String modelVersion, String featureVersion,
                                     Instant queryAsOf, List<RecommendationItem> items, String nextCursor) {
    }

    @Transactional
    public RecommendationPage recommend(UUID userId, String section, Integer limit, String cursor) {
        int pageSize = limit == null || limit < 1 || limit > 50 ? 10 : limit;
        Instant queryAsOf = clock.now();
        String modelVersion = MODEL_V0;
        UUID requestId;
        List<UUID> candidates;

        if (cursor != null && !cursor.isBlank()) {
            SearchCursor decoded = cursorCodec.decode(cursor);
            requestId = UUID.fromString(String.valueOf(decoded.last().get(0)));
            int offset = decoded.last().get(1) instanceof Number n ? n.intValue() : 0;
            record Stored(String candidatesJson, String model, Instant queryAsOf) {
            }
            List<Stored> stored = jdbc.query("""
                    SELECT candidate_ids::text, model_version, query_as_of FROM recommendation_requests
                    WHERE id = ? AND expires_at > now()
                    """, (rs, i) -> new Stored(rs.getString(1), rs.getString(2),
                    rs.getObject(3, OffsetDateTime.class).toInstant()), requestId);
            if (stored.isEmpty()) {
                throw new ApiException(ErrorCode.CURSOR_EXPIRED, "recommendation result expired");
            }
            modelVersion = stored.getFirst().model();
            queryAsOf = stored.getFirst().queryAsOf();
            candidates = parseIds(stored.getFirst().candidatesJson());
            return pageFrom(requestId, modelVersion, queryAsOf, candidates, offset, pageSize);
        }

        // Fresh request: build, freeze and serve the first page.
        List<Cand> scored = score(userId, section);
        candidates = scored.stream().limit(60).map(Cand::id).toList();
        requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recommendation_requests (id, user_id, section, model_version, feature_version,
                                                     query_as_of, candidate_ids, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, requestId, userId, section, modelVersion, FEATURE_VERSION,
                java.sql.Timestamp.from(queryAsOf), jsonOfIds(scored),
                java.sql.Timestamp.from(queryAsOf.plusSeconds(900)));
        return pageFrom(requestId, modelVersion, queryAsOf, candidates, 0, pageSize);
    }

    private RecommendationPage pageFrom(UUID requestId, String modelVersion, Instant queryAsOf,
            List<UUID> candidates, int offset, int pageSize) {
        List<RecommendationItem> items = new ArrayList<>();
        int served = 0;
        int index = offset;
        while (index < candidates.size() && served < pageSize) {
            UUID candidate = candidates.get(index);
            RecommendationItem item = present(candidate);
            if (item != null) {
                items.add(item);
                served++;
            }
            index++;
        }
        String nextCursor = null;
        if (index < candidates.size()) {
            SearchCursor next = new SearchCursor(SearchCursor.CURRENT_VERSION, "rec", "rec",
                    List.of(requestId.toString(), index), queryAsOf, cursorCodec.newExpiry());
            nextCursor = cursorCodec.encode(next);
        }
        return new RecommendationPage(requestId.toString(), modelVersion, FEATURE_VERSION, queryAsOf, items,
                nextCursor);
    }

    /** Display-time re-filter: cancelled, ended or hidden events never render. */
    private RecommendationItem present(UUID eventId) {
        return jdbc.query("""
                SELECT e.id, e.title, e.category, e.starts_at, v.city,
                       (SELECT COUNT(*) FROM interactions i WHERE i.event_id = e.id
                          AND i.type IN ('VIEW', 'IMPRESSION')) AS popularity
                FROM events e LEFT JOIN venues v ON v.id = e.venue_id
                WHERE e.id = ? AND e.status = 'PUBLISHED' AND e.ends_at > now()
                """, (rs, i) -> new RecommendationItem(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("category"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(), rs.getString("city"),
                rs.getDouble("popularity"), List.of(reasonFor(rs.getDouble("popularity")))), eventId)
                .stream().findFirst().orElse(null);
    }

    private String reasonFor(double popularity) {
        return popularity > 0 ? "POPULAR_IN_CATEGORY" : "UPCOMING_FOR_YOU";
    }

    private List<Cand> score(UUID userId, String section) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.title, e.category, e.starts_at, v.city,
                  (SELECT COUNT(*) FROM interactions i WHERE i.event_id = e.id
                     AND i.type IN ('VIEW', 'IMPRESSION') AND i.received_at > now() - interval '7 days')
                    AS views7d,
                  (SELECT COUNT(*) FROM saved_events s WHERE s.event_id = e.id) AS saves
                FROM events e LEFT JOIN venues v ON v.id = e.venue_id
                WHERE e.status = 'PUBLISHED' AND e.ends_at > now()
                  AND COALESCE((SELECT SUM(i.available) FROM ticket_tiers t
                        JOIN inventory i ON i.tier_id = t.id WHERE t.event_id = e.id), 0) > 0
                """);
        List<Object> params = new ArrayList<>();
        if (userId != null) {
            // Hard filter: exclude severe time conflicts with confirmed orders.
            sql.append("""
                    AND NOT EXISTS (
                      SELECT 1 FROM bookings b JOIN events be ON be.id = b.event_id
                      WHERE b.user_id = ? AND b.status = 'CONFIRMED'
                        AND e.starts_at < be.ends_at AND e.ends_at > be.starts_at
                    )
                    """);
            params.add(userId);
        }
        sql.append(" ORDER BY e.starts_at ASC LIMIT 200");
        List<Raw> raw = jdbc.query(sql.toString(), (rs, i) -> new Raw(rs.getObject("id", UUID.class),
                rs.getString("title"), rs.getString("category"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getString("city"), rs.getLong("views7d"), rs.getLong("saves")), params.toArray());
        Map<String, List<String>> userCategories = preferredCategories(userId);
        List<Cand> scored = new ArrayList<>();
        for (Raw r : raw) {
            double score = r.views7d() * 1.0 + r.saves() * 3.0;
            List<String> reasons = new ArrayList<>();
            if (r.views7d() + r.saves() > 0) {
                reasons.add("TRENDING_7D");
            }
            if (userCategories.containsKey(r.category())) {
                score += 5;
                reasons.add("MATCHES_PREFERENCE");
            }
            if ("nearby".equals(section) && r.city() != null) {
                reasons.add("NEAR_YOU");
            }
            if (reasons.isEmpty()) {
                reasons.add("UPCOMING");
            }
            scored.add(new Cand(r.id(), r.title(), r.category(), r.startsAt(), r.city(), score, reasons));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored;
    }

    record Cand(UUID id, String title, String category, Instant startsAt, String city, double score,
                List<String> reasons) {
    }

    record Raw(UUID id, String title, String category, Instant startsAt, String city, long views7d, long saves) {
    }

    private Map<String, List<String>> preferredCategories(UUID userId) {
        Map<String, List<String>> out = new HashMap<>();
        if (userId == null) {
            return out;
        }
        List<String[]> rows = jdbc.query("""
                SELECT categories FROM user_preferences WHERE user_id = ?
                """, (rs, i) -> new String[] { rs.getArray("categories") == null ? "{}"
                        : rs.getArray("categories").toString() }, userId);
        if (!rows.isEmpty()) {
            String raw = rows.getFirst()[0];
            String cleaned = raw.replace("{", "").replace("}", "").replace("\"", "");
            for (String category : cleaned.split(",")) {
                if (!category.isBlank()) {
                    out.put(category.trim(), List.of());
                }
            }
        }
        return out;
    }

    private String jsonOfIds(List<Cand> scored) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < scored.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(scored.get(i).id()).append('"');
        }
        return sb.append("]").toString();
    }

    private List<UUID> parseIds(String json) {
        List<UUID> ids = new ArrayList<>();
        for (String id : json.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
            if (!id.isBlank()) {
                ids.add(UUID.fromString(id.trim()));
            }
        }
        return ids;
    }
}
