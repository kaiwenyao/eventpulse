package dev.kaiwen.eventpulse.service.impl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.CursorCodec;
import dev.kaiwen.eventpulse.common.DbClock;
import dev.kaiwen.eventpulse.common.web.RateLimiter;
import dev.kaiwen.eventpulse.dto.SearchCursor;
import dev.kaiwen.eventpulse.exception.ApiException;
import dev.kaiwen.eventpulse.exception.ErrorCode;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.service.EmbeddingService;
import dev.kaiwen.eventpulse.service.RecommendationService;

import org.springframework.beans.factory.annotation.Qualifier;
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
 *
 * <p>Resource bulkhead (plan §10.3): scoring/candidate/display queries run on
 * the read-only search pool with a short statement timeout, guarded by a
 * concurrency bulkhead; when the bulkhead is saturated or a query fails or
 * times out, serving degrades to the cached popular list instead of
 * competing with transactional traffic.
 */
@Service
public class RecommendationServiceImpl implements RecommendationService {

    private static final String MODEL_FALLBACK = "V0_FALLBACK";

    private final JdbcTemplate jdbc;
    private final JdbcTemplate searchJdbc;
    private final CursorCodec cursorCodec;
    private final EmbeddingService embeddingService;
    private final DbClock clock;
    private final OutboxWriter outbox;
    private final RateLimiter rateLimiter;
    private final java.util.concurrent.Semaphore bulkhead;
    private final long bulkheadWaitMs;
    private final PopularEventsCache popularEventsCache;

    public RecommendationServiceImpl(@Qualifier("txJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("searchJdbcTemplate") JdbcTemplate searchJdbc, CursorCodec cursorCodec,
            EmbeddingService embeddingService, DbClock clock, OutboxWriter outbox, RateLimiter rateLimiter,
            @org.springframework.beans.factory.annotation.Value(
                    "${eventpulse.recommendation.bulkhead-permits:8}") int bulkheadPermits,
            @org.springframework.beans.factory.annotation.Value(
                    "${eventpulse.recommendation.bulkhead-wait-ms:100}") long bulkheadWaitMs,
            PopularEventsCache popularEventsCache) {
        this.jdbc = jdbc;
        this.searchJdbc = searchJdbc;
        this.cursorCodec = cursorCodec;
        this.embeddingService = embeddingService;
        this.clock = clock;
        this.outbox = outbox;
        this.rateLimiter = rateLimiter;
        this.bulkhead = new java.util.concurrent.Semaphore(Math.max(1, bulkheadPermits));
        this.bulkheadWaitMs = bulkheadWaitMs;
        this.popularEventsCache = popularEventsCache;
    }

    @Override
    @Transactional
    public RecommendationPage recommend(UUID userId, String section, Integer limit, String cursor) {
        int pageSize = limit == null || limit < 1 || limit > 50 ? 10 : limit;
        Instant queryAsOf = clock.now();
        String modelVersion = embeddingService.isVectorAvailable() ? MODEL_V1 : MODEL_V0;
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
            return pageFrom(requestId, modelVersion, queryAsOf, candidates, offset, pageSize, java.util.Map.of());
        }

        // Fresh request: build, freeze and serve the first page. Scoring runs
        // under the concurrency bulkhead on the search pool; saturation or a
        // failed/timed-out query degrades to the cached popular list (§10.3)
        // instead of piling up against transactional work.
        boolean acquired;
        try {
            acquired = bulkhead.tryAcquire(bulkheadWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "recommendation temporarily unavailable");
        }
        if (!acquired) {
            return popularFallback(userId, section, queryAsOf, pageSize);
        }
        List<Cand> scored;
        try {
            scored = score(userId, section, modelVersion);
        }
        catch (org.springframework.dao.DataAccessException e) {
            // Statement timeout / pool exhaustion / transient failure → degrade.
            return popularFallback(userId, section, queryAsOf, pageSize);
        }
        finally {
            bulkhead.release();
        }
        candidates = scored.stream().limit(60).map(Cand::id).toList();
        requestId = UUID.randomUUID();
        Map<UUID, List<String>> rankedReasons = new HashMap<>();
        for (Cand c : scored) {
            rankedReasons.putIfAbsent(c.id(), c.reasons());
        }
        jdbc.update("""
                INSERT INTO recommendation_requests (id, user_id, section, model_version, feature_version,
                                                     query_as_of, candidate_ids, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, requestId, userId, section, modelVersion, FEATURE_VERSION,
                java.sql.Timestamp.from(queryAsOf), jsonOfIds(scored),
                java.sql.Timestamp.from(queryAsOf.plusSeconds(900)));
        return pageFrom(requestId, modelVersion, queryAsOf, candidates, 0, pageSize, rankedReasons);
    }

    private RecommendationPage pageFrom(UUID requestId, String modelVersion, Instant queryAsOf,
            List<UUID> candidates, int offset, int pageSize, Map<UUID, List<String>> rankedReasons) {
        List<RecommendationItem> items = new ArrayList<>();
        int served = 0;
        int index = offset;
        while (index < candidates.size() && served < pageSize) {
            UUID candidate = candidates.get(index);
            RecommendationItem item = present(candidate, rankedReasons.get(candidate));
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

    /**
     * Display-time re-filter: cancelled, ended or hidden events never render.
     * When ranking reasons are available (fresh request) they carry the
     * verifiable signal (EMBEDDING_MATCH, MATCHES_PREFERENCE, ...); for cursor
     * pages only the display-time popularity reason is available.
     */
    private RecommendationItem present(UUID eventId, List<String> rankedReasons) {
        // Display-time re-filter belongs to the recommendation read path: it
        // runs on the read-only search pool, never on the transactional pool.
        return searchJdbc.query("""
                SELECT e.id, e.title, e.category, e.starts_at, v.city,
                       (SELECT COUNT(*) FROM interactions i WHERE i.event_id = e.id
                          AND i.type IN ('VIEW', 'IMPRESSION')) AS popularity
                FROM events e LEFT JOIN venues v ON v.id = e.venue_id
                WHERE e.id = ? AND e.status = 'PUBLISHED' AND e.ends_at > now()
                """, (rs, i) -> new RecommendationItem(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("category"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(), rs.getString("city"),
                rs.getDouble("popularity"), reasonsFor(rs.getDouble("popularity"), rankedReasons)), eventId)
                .stream().findFirst().orElse(null);
    }

    /**
     * Degrade path (§10.3): serve the cached popular list without any further
     * DB read. Candidates are the cached display rows; the frozen-request row
     * is still written on the transactional pool so cursor paging keeps its
     * contract (if even that write fails, no cursor is emitted).
     */
    private RecommendationPage popularFallback(UUID userId, String section, Instant queryAsOf, int pageSize) {
        List<PopularEventsCache.CachedEvent> popular = popularEventsCache.popular();
        List<RecommendationItem> items = new ArrayList<>();
        for (PopularEventsCache.CachedEvent cached : popular) {
            if (items.size() >= Math.min(pageSize, popular.size())) {
                break;
            }
            items.add(new RecommendationItem(cached.id(), cached.title(), cached.category(), cached.startsAt(),
                    cached.city(), (double) cached.popularity(), List.of(PopularEventsCache.FALLBACK_REASON)));
        }
        UUID requestId = UUID.randomUUID();
        String nextCursor = null;
        try {
            jdbc.update("""
                    INSERT INTO recommendation_requests (id, user_id, section, model_version, feature_version,
                                                         query_as_of, candidate_ids, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, requestId, userId, section, MODEL_FALLBACK, FEATURE_VERSION,
                    java.sql.Timestamp.from(queryAsOf), jsonOfCachedIds(popular),
                    java.sql.Timestamp.from(queryAsOf.plusSeconds(900)));
            if (popular.size() > pageSize) {
                SearchCursor next = new SearchCursor(SearchCursor.CURRENT_VERSION, "rec", "rec",
                        List.of(requestId.toString(), pageSize), queryAsOf, cursorCodec.newExpiry());
                nextCursor = cursorCodec.encode(next);
            }
        }
        catch (org.springframework.dao.DataAccessException e) {
            // Degrade means degrade: serve without cursor rather than fail.
        }
        return new RecommendationPage(requestId.toString(), MODEL_FALLBACK, FEATURE_VERSION, queryAsOf, items,
                nextCursor);
    }

    private String jsonOfCachedIds(List<PopularEventsCache.CachedEvent> popular) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < popular.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(popular.get(i).id()).append('"');
        }
        return sb.append("]").toString();
    }

    private List<String> reasonsFor(double popularity, List<String> rankedReasons) {
        if (rankedReasons != null && !rankedReasons.isEmpty()) {
            return rankedReasons;
        }
        return List.of(reasonFor(popularity));
    }

    private String reasonFor(double popularity) {
        return popularity > 0 ? "POPULAR_IN_CATEGORY" : "UPCOMING_FOR_YOU";
    }

    private List<Cand> score(UUID userId, String section, String modelVersion) {
        Map<String, List<String>> userCategories = preferredCategories(userId);
        // V1 builds a preference vector from the user's preferred categories and
        // ranks by cosine similarity to each event's text embedding. V0 stays on
        // popularity + hard filters.
        boolean useEmbedding = MODEL_V1.equals(modelVersion) && embeddingService.isVectorAvailable();
        String preferenceLiteral = null;
        if (useEmbedding && !userCategories.isEmpty()) {
            String joined = String.join(" ", userCategories.keySet());
            preferenceLiteral = toPgVectorLiteral(embeddingService.embed(joined.toLowerCase()));
        }
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.title, e.category, e.starts_at, v.city,
                  (SELECT COUNT(*) FROM interactions i WHERE i.event_id = e.id
                     AND i.type IN ('VIEW', 'IMPRESSION') AND i.received_at > now() - interval '7 days')
                    AS views7d,
                  (SELECT COUNT(*) FROM saved_events s WHERE s.event_id = e.id) AS saves
                """);
        if (useEmbedding && preferenceLiteral != null) {
            // cosine distance <=> in [0,2]; convert to similarity in [0,1] for scoring.
            sql.append(", 1 - (e.embedding <=> ?::vector) AS embedding_similarity");
        }
        else {
            sql.append(", 0.0 AS embedding_similarity");
        }
        sql.append("""
                 FROM events e LEFT JOIN venues v ON v.id = e.venue_id
                WHERE e.status = 'PUBLISHED' AND e.ends_at > now()
                  AND COALESCE((SELECT SUM(i.available) FROM ticket_tiers t
                        JOIN inventory i ON i.tier_id = t.id WHERE t.event_id = e.id), 0) > 0
                """);
        List<Object> params = new ArrayList<>();
        if (useEmbedding && preferenceLiteral != null) {
            params.add(preferenceLiteral);
        }
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
        List<Raw> raw = searchJdbc.query(sql.toString(), (rs, i) -> new Raw(rs.getObject("id", UUID.class),
                rs.getString("title"), rs.getString("category"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getString("city"), rs.getLong("views7d"), rs.getLong("saves"),
                rs.getDouble("embedding_similarity")), params.toArray());
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
            if (r.embeddingSimilarity() > 0) {
                // Similarity contributes a bounded, non-negative term; it never
                // outweighs a hard conflict and degrades to no-op at 0.
                score += r.embeddingSimilarity() * 4.0;
                reasons.add("EMBEDDING_MATCH");
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

    record Cand(UUID id, String title, String category, Instant startsAt, String city, double score,
                List<String> reasons) {
    }

    record Raw(UUID id, String title, String category, Instant startsAt, String city, long views7d, long saves,
               double embeddingSimilarity) {
    }

    private Map<String, List<String>> preferredCategories(UUID userId) {
        Map<String, List<String>> out = new HashMap<>();
        if (userId == null) {
            return out;
        }
        // Preference lookup is part of the ranking read path: search pool.
        List<String[]> rows = searchJdbc.query("""
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

    // ---------------------------------------------------------- interactions

    @Override
    public int recordInteractions(UUID userId, String sessionId, InteractionBatch batch) {
        rateLimiter.check("interactions",
                userId != null ? userId.toString()
                        : "session:" + (batch.sessionId() == null ? "anon" : batch.sessionId()));
        if (batch.requestId() == null || batch.requestId().isBlank() || batch.requestId().length() > 80) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "requestId required",
                    Map.of("requestId", "required, max 80 chars"));
        }
        if (batch.events() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "events required");
        }
        if (batch.events().size() > 50) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "batch too large",
                    Map.of("events", "max 50 per batch"));
        }
        int accepted = 0;
        UUID aggregateId = userId != null ? userId
                : UUID.nameUUIDFromBytes((batch.sessionId() == null ? "anon" : batch.sessionId()).getBytes());
        for (InteractionInput input : batch.events()) {
            if (input.eventId() == null || input.type() == null
                    || !List.of("VIEW", "IMPRESSION", "SAVE", "UNSAVE", "SHARE", "BOOK_ATTEMPT")
                            .contains(input.type())) {
                continue;
            }
            int inserted = jdbc.update("""
                    INSERT INTO interactions (request_id, user_id, session_id, event_id, type, position,
                                              occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, batch.requestId(), userId, batch.sessionId(),
                    input.eventId(), input.type(), input.position(),
                    input.occurredAt() == null ? null : java.sql.Timestamp.from(input.occurredAt()));
            if (inserted == 1) {
                accepted++;
                outbox.append(userId == null ? "session" : "user", aggregateId, OutboxWriter.TOPIC_INTERACTION,
                        "interaction.recorded", Map.of("eventId", input.eventId().toString(), "type",
                                input.type(), "requestId", batch.requestId()));
            }
        }
        return accepted;
    }
}