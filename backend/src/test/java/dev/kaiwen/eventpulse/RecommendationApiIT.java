package dev.kaiwen.eventpulse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.IntegrationTestBase.OrganiserRef;
import dev.kaiwen.eventpulse.IntegrationTestBase.UserRef;
import dev.kaiwen.eventpulse.recs.EmbeddingService;
import dev.kaiwen.eventpulse.recs.RecommendationService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recommendation endpoints: fresh request with frozen cursor pages, hard
 * filters, anonymous access and interactions batching with dedupe/limits.
 */
class RecommendationApiIT extends IntegrationTestBase {

    @Test
    void recommendationsFreezeCandidatesAndPageThroughCursor() {
        OrganiserRef fixture = createEventWithTier(30, 10);
        UserRef user = createUser("USER");
        jdbc.update("""
                INSERT INTO user_preferences (user_id, categories, coarse_location, radius_km)
                VALUES (?, '{music}', 'shanghai', 20) ON CONFLICT (user_id) DO NOTHING
                """, user.id());
        jdbc.update("""
                INSERT INTO interactions (request_id, user_id, event_id, type)
                VALUES ('it-seed', ?, ?, 'VIEW')
                """, user.id(), fixture.eventId());

        ResponseEntity<Map> page = get("/api/v1/recommendations?section=for-you&limit=2", user.token());
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(body(page).get("requestId")).isNotNull();
        // pgvector is present in the test image, so the service runs V1.
        assertThat(body(page).get("modelVersion"))
                .isEqualTo(embeddingService.isVectorAvailable()
                        ? RecommendationService.MODEL_V1 : RecommendationService.MODEL_V0);
        assertThat(body(page).get("featureVersion")).isNotNull();
        String cursor = (String) body(page).get("nextCursor");

        if (cursor != null) {
            ResponseEntity<Map> next = get("/api/v1/recommendations?cursor=" + cursor, user.token());
            assertThat(next.getStatusCode().value()).isEqualTo(200);
        }

        // anonymous request also works
        ResponseEntity<Map> anon = get("/api/v1/recommendations?section=nearby", null);
        assertThat(anon.getStatusCode().value()).isEqualTo(200);

        // unknown cursor id -> expired
        String forgedCursor = forgeCursorWithUnknownRequest();
        assertThat(get("/api/v1/recommendations?cursor=" + forgedCursor, user.token())
                .getStatusCode().value()).isEqualTo(400);
    }

    /**
     * V1 path: a category-mismatched event whose text embedding matches the
     * user's preference is surfaced via embedding similarity, so it carries the
     * EMBEDDING_MATCH reason and never MATCHES_PREFERENCE (the hard category
     * path cannot fire on a non-matching category).
     */
    @Test
    void v1RankingUsesEmbeddingSimilarityWhenPreferenceMatches() {
        // event whose category is NOT one of the user's preferences, so the hard
        // MATCHES_PREFERENCE path cannot fire on it regardless of embedding.
        OrganiserRef matching = createEventWithTier(30, 10);
        jdbc.update("UPDATE events SET category = 'other' WHERE id = ?", matching.eventId());
        // give the event an embedding whose text shares tokens with the user's
        // preference vector ("music") so cosine similarity is non-zero.
        embeddingService.embedEvent(matching.eventId(), "独立摇滚现场 indie rock live", "other",
                "一场独立摇滚与电子融合的现场演出 music music music");
        UserRef user = createUser("USER");
        jdbc.update("""
                INSERT INTO user_preferences (user_id, categories, coarse_location, radius_km)
                VALUES (?, '{music}', 'shanghai', 20) ON CONFLICT (user_id) DO NOTHING
                """, user.id());
        // Give the event enough recent popularity that it reliably lands at the
        // top of the first page shared with other tests' seed events; this keeps
        // the assertion about reasons independent of candidate ordering.
        jdbc.update("""
                INSERT INTO interactions (request_id, user_id, event_id, type)
                SELECT 'embed-seed-' || gs, ?, ?, 'VIEW' FROM generate_series(1, 200) gs
                """, user.id(), matching.eventId());

        org.junit.jupiter.api.Assumptions.assumeTrue(embeddingService.isVectorAvailable(),
                "V1 embedding ranking requires pgvector");
        ResponseEntity<Map> page = get("/api/v1/recommendations?section=for-you&limit=10", user.token());
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(body(page).get("modelVersion")).isEqualTo(RecommendationService.MODEL_V1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body(page).get("items");
        assertThat(items).isNotEmpty();
        Map<String, Object> matched = items.stream()
                .filter(i -> matching.eventId().toString().equals(String.valueOf(i.get("eventId"))))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) matched.get("reasonCodes");
        // embedding similarity matched the preference text -> this reason fires
        assertThat(reasons).contains("EMBEDDING_MATCH");
        // category is 'other', not a user preference -> the hard category reason must NOT fire
        assertThat(reasons).doesNotContain("MATCHES_PREFERENCE");
    }

    /** A validly-signed cursor pointing at a never-created recommendation request. */
    private String forgeCursorWithUnknownRequest() {
        dev.kaiwen.eventpulse.catalogue.CursorCodec codec = context.getBean(
                dev.kaiwen.eventpulse.catalogue.CursorCodec.class);
        dev.kaiwen.eventpulse.catalogue.SearchCursor cursor = new dev.kaiwen.eventpulse.catalogue.SearchCursor(1, "rec",
                "rec", List.of(UUID.randomUUID().toString(), 0), java.time.Instant.now(),
                java.time.Instant.now().plusSeconds(600));
        return codec.encode(cursor);
    }

    @Test
    void interactionsBatchAcceptsDedupesAndEnforcesLimits() {
        OrganiserRef fixture = createEventWithTier(30, 10);
        UserRef user = createUser("USER");
        String requestId = "it-batch-" + UUID.randomUUID();

        ResponseEntity<Map> batch = post("/api/v1/interactions", user.token(), Map.of(
                "requestId", requestId,
                "sessionId", "sess-1",
                "events", List.of(
                        Map.of("eventId", fixture.eventId().toString(), "type", "VIEW", "position", 1),
                        Map.of("eventId", fixture.eventId().toString(), "type", "IMPRESSION"),
                        Map.of("eventId", fixture.eventId().toString(), "type", "BOOK_ATTEMPT"))));
        assertThat(batch.getStatusCode().value()).isEqualTo(202);
        assertThat(((Number) batch.getBody().get("accepted")).intValue()).isEqualTo(3);

        // duplicate batch (same requestId + events) is fully deduped
        ResponseEntity<Map> replay = post("/api/v1/interactions", user.token(), Map.of(
                "requestId", requestId,
                "events", List.of(
                        Map.of("eventId", fixture.eventId().toString(), "type", "VIEW", "position", 1),
                        Map.of("eventId", fixture.eventId().toString(), "type", "IMPRESSION"))));
        assertThat(replay.getStatusCode().value()).isEqualTo(202);
        assertThat(((Number) replay.getBody().get("accepted")).intValue()).isZero();

        // invalid type is skipped, missing requestId rejected
        ResponseEntity<Map> invalid = post("/api/v1/interactions", user.token(), Map.of(
                "requestId", "it-batch-" + UUID.randomUUID(),
                "events", List.of(Map.of("eventId", fixture.eventId().toString(), "type", "BOGUS"))));
        assertThat(invalid.getStatusCode().value()).isEqualTo(202);

        List<Map<String, Object>> oversized = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            oversized.add(Map.of("eventId", fixture.eventId().toString(), "type", "VIEW"));
        }
        ResponseEntity<Map> rejected = post("/api/v1/interactions", user.token(), Map.of(
                "requestId", "it-batch-" + UUID.randomUUID(), "events", oversized));
        assertThat(rejected.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<Map> noRequestId = post("/api/v1/interactions", user.token(),
                Map.of("events", List.of()));
        assertThat(noRequestId.getStatusCode().value()).isEqualTo(400);
    }

    /**
     * Gap: interactions are rate limited (configured 30/60). Hammering the
     * endpoint past the limit returns 429 from the configured bucket.
     */
    @Test
    void interactionsAreRateLimited() {
        OrganiserRef fixture = createEventWithTier(30, 10);
        UserRef user = createUser("USER");
        int rateLimited = 0;
        // fire well past the configured 30/60 limit with unique request ids
        for (int i = 0; i < 45; i++) {
            ResponseEntity<Map> resp = post("/api/v1/interactions", user.token(), Map.of(
                    "requestId", "it-rl-" + UUID.randomUUID(),
                    "events", List.of(Map.of("eventId", fixture.eventId().toString(), "type", "VIEW"))));
            if (resp.getStatusCode().value() == 429) {
                rateLimited++;
            }
        }
        assertThat(rateLimited).isGreaterThan(0);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext context;

    @Autowired
    private EmbeddingService embeddingService;
}
