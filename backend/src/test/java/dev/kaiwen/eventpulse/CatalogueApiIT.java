package dev.kaiwen.eventpulse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.IntegrationTestBase.OrganiserRef;
import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.IntegrationTestBase.UserRef;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalogue surface: keyset search with every filter and sort, nearby,
 * ETag detail, saved events, organiser lifecycle (draft -> publish ->
 * update/cancel + batch), capacity guard and funnel.
 */
class CatalogueApiIT extends IntegrationTestBase {

    private UserRef organiserUser(OrganiserRef fixture) {
        UserRef organiserUser = createUser("ORGANISER");
        jdbc.update("UPDATE organisers SET owner_user_id = ? WHERE id = ?", organiserUser.id(),
                fixture.organiserId());
        return organiserUser;
    }

    @Test
    void searchSupportsSortsFiltersAndKeysetCursor() {
        OrganiserRef fixture = createEventWithTier("CNY", 12345L, 100, 10);
        jdbc.update("UPDATE ticket_tiers SET unit_price_minor = ? WHERE id = ?", 12345L, fixture.tierId());
        // search only surfaces upcoming events; move the fixture into the future
        jdbc.update("UPDATE events SET starts_at = now() + interval '6 days', "
                + "ends_at = now() + interval '7 days' WHERE id = ?", fixture.eventId());
        UserRef viewer = createUser("USER");

        ResponseEntity<Map> byPrice = get("/api/v1/events?sort=price&availableOnly=true", viewer.token());
        assertThat(byPrice.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> items = (List<Map<String, Object>>) body(byPrice).get("items");
        assertThat(items).isNotEmpty();

        ResponseEntity<Map> byNewest = get("/api/v1/events?sort=newest", viewer.token());
        assertThat(byNewest.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> filtered = get("/api/v1/events?category=music&city=%E4%B8%8A%E6%B5%B7"
                + "&priceMin=100&priceMax=99999&from=2020-01-01T00:00:00Z&to=2030-01-01T00:00:00Z"
                + "&q=Test&limit=1", viewer.token());
        List<Map<String, Object>> page1 = (List<Map<String, Object>>) body(filtered).get("items");
        String nextCursor = (String) body(filtered).get("nextCursor");
        if (page1.size() == 1 && nextCursor != null) {
            ResponseEntity<Map> page2 = get("/api/v1/events?category=music&city=%E4%B8%8A%E6%B5%B7"
                    + "&priceMin=100&priceMax=99999&from=2020-01-01T00:00:00Z&to=2030-01-01T00:00:00Z"
                    + "&q=Test&limit=1&cursor=" + nextCursor, viewer.token());
            assertThat(page2.getStatusCode().value()).isEqualTo(200);
        }

        // filters changed with the same cursor -> 400
        ResponseEntity<Map> mismatch = get("/api/v1/events?category=sports&limit=1&cursor=" + nextCursor,
                viewer.token());
        assertThat(mismatch.getStatusCode().value()).isEqualTo(400);

        // invalid sort
        assertThat(get("/api/v1/events?sort=bogus", viewer.token()).getStatusCode().value()).isEqualTo(400);

        // bad cursor signature
        assertThat(get("/api/v1/events?cursor=xx.yy", viewer.token()).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void nearbyAndDetailWithEtag() {
        OrganiserRef fixture = createEventWithTier(50, 5);
        UserRef viewer = createUser("USER");

        ResponseEntity<Map> nearby = get("/api/v1/events/nearby?lat=31.23&lng=121.47&radiusKm=10", viewer.token());
        assertThat(nearby.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) body(nearby).get("items")).isNotEmpty();

        ResponseEntity<Map> detail = get("/api/v1/events/" + fixture.eventId(), viewer.token());
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        String etag = detail.getHeaders().getFirst("ETag");
        assertThat(etag).isNotBlank();

        ResponseEntity<Map> notModified = exchange("GET", "/api/v1/events/" + fixture.eventId(),
                viewer.token(), null, Map.of("If-None-Match", etag));
        assertThat(notModified.getStatusCode().value()).isEqualTo(304);
    }

    @Test
    void savedEventsLifecycle() {
        OrganiserRef fixture = createEventWithTier(50, 5);
        UserRef user = createUser("USER");

        assertThat(exchange("PUT", "/api/v1/me/saved-events/" + fixture.eventId(), user.token(), null, null)
                .getStatusCode().value()).isEqualTo(204);
        // idempotent second save
        assertThat(exchange("PUT", "/api/v1/me/saved-events/" + fixture.eventId(), user.token(), null, null)
                .getStatusCode().value()).isEqualTo(204);

        ResponseEntity<Map> list = get("/api/v1/me/saved-events", user.token());
        assertThat(((List<?>) body(list).get("items")).size()).isGreaterThanOrEqualTo(1);

        assertThat(exchange("DELETE", "/api/v1/me/saved-events/" + fixture.eventId(), user.token(), null, null)
                .getStatusCode().value()).isEqualTo(204);
        // unsaving something already gone -> 204 anyway (idempotent)
        assertThat(exchange("DELETE", "/api/v1/me/saved-events/" + fixture.eventId(), user.token(), null, null)
                .getStatusCode().value()).isEqualTo(204);

        // saving an unpublished event is a hidden 404
        List<UUID> drafts = jdbc.queryForList("SELECT id FROM events WHERE status = 'DRAFT' LIMIT 1",
                UUID.class);
        UUID draftEvent;
        if (drafts.isEmpty()) {
            // create a draft quickly via SQL
            UUID organiserId = jdbc.queryForObject("SELECT id FROM organisers LIMIT 1", UUID.class);
            draftEvent = jdbc.queryForObject("""
                    INSERT INTO events (organiser_id, title, category, status, starts_at, ends_at, policy)
                    VALUES (?, 'draft-it', 'music', 'DRAFT', now() + interval '2 days',
                            now() + interval '3 days', '{}'::jsonb)
                    RETURNING id
                    """, UUID.class, organiserId);
        }
        else {
            draftEvent = drafts.getFirst();
        }
        ResponseEntity<Map> hidden = exchange("PUT", "/api/v1/me/saved-events/" + draftEvent, user.token(),
                null, null);
        assertThat(hidden.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void organiserLifecycleFromDraftToCancelledWithBatch() {
        OrganiserRef fixture = createEventWithTier(20, 5);
        UserRef organiser = organiserUser(fixture);
        UserRef buyer = createUser("USER");

        // draft -> edit -> publish
        ResponseEntity<Map> created = post("/api/v1/organiser/events", organiser.token(), Map.of(
                "title", "Org IT Event", "description", "d", "category", "tech",
                "startsAt", Instant.now().plusSeconds(86400).toString(),
                "endsAt", Instant.now().plusSeconds(90000).toString(),
                "policy", Map.of("cancellable", true, "cancellationDeadlineHoursBeforeStart", 0,
                        "resaleAllowed", false, "version", 1),
                "venue", Map.of("name", "IT Hall", "city", "上海", "lat", 31.2, "lng", 121.4),
                "tiers", List.of(Map.of("name", "早鸟", "currency", "CNY", "unitPriceMinor", 8800,
                        "saleStartAt", Instant.now().minusSeconds(60).toString(),
                        "saleEndAt", Instant.now().plusSeconds(80000).toString(),
                        "perUserLimit", 4, "capacity", 15))));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        UUID eventId = UUID.fromString((String) body(created).get("eventId"));

        // invalid payload -> 400
        assertThat(post("/api/v1/organiser/events", organiser.token(), Map.of("title", "x"))
                .getStatusCode().value()).isEqualTo(400);

        ResponseEntity<Map> update = exchange("PUT", "/api/v1/organiser/events/" + eventId, organiser.token(),
                Map.of("description", "updated"), null);
        assertThat(update.getStatusCode().value()).isEqualTo(204);
        assertThat(post("/api/v1/organiser/events/" + eventId + "/publish", organiser.token(), null, null)
                .getStatusCode().value()).isEqualTo(204);
        // publishing twice -> 409
        assertThat(post("/api/v1/organiser/events/" + eventId + "/publish", organiser.token(), null, null)
                .getStatusCode().value()).isEqualTo(409);

        // capacity adjustments via If-Match
        UUID tierId = jdbc.queryForObject(
                "SELECT id FROM ticket_tiers WHERE event_id = ? LIMIT 1", UUID.class, eventId);
        long version = jdbc.queryForObject("SELECT version FROM inventory WHERE tier_id = ?", Long.class,
                tierId);
        assertThat(exchange("PATCH", "/api/v1/organiser/tiers/" + tierId + "/inventory", organiser.token(),
                Map.of("capacity", 18), Map.of("If-Match", String.valueOf(version))).getStatusCode().value())
                .isEqualTo(204);
        // stale version -> 409
        assertThat(exchange("PATCH", "/api/v1/organiser/tiers/" + tierId + "/inventory", organiser.token(),
                Map.of("capacity", 19), Map.of("If-Match", String.valueOf(version))).getStatusCode().value())
                .isEqualTo(409);
        // below reserved+sold+withheld -> 409 (move stock within the equation)
        jdbc.update("UPDATE inventory SET reserved = 5, available = available - 5 WHERE tier_id = ?",
                tierId);
        assertThat(exchange("PATCH", "/api/v1/organiser/tiers/" + tierId + "/inventory", organiser.token(),
                Map.of("capacity", 3), null).getStatusCode().value()).isEqualTo(409);
        jdbc.update("UPDATE inventory SET reserved = 0, available = available + 5 WHERE tier_id = ?",
                tierId);

        // funnel shows the event
        ResponseEntity<Map> funnel = get("/api/v1/organiser/funnel", organiser.token());
        assertThat(funnel.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) body(funnel).get("items")).isNotEmpty();

        // a buyer books then organiser cancels the event -> batch cancels the order
        assertThat(post("/api/v1/bookings", buyer.token(),
                Map.of("eventId", eventId.toString(), "tierId", tierId.toString(), "quantity", 1,
                        "ageConfirmed", true),
                CanonicalJson.newOpaqueToken()).getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map> cancelled = post("/api/v1/organiser/events/" + eventId + "/cancel",
                organiser.token(), Map.of("reason", "it"));
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(body(cancelled).get("cancelled")).isEqualTo(1);
        String bookingStatus = jdbc.queryForObject("SELECT status FROM bookings WHERE event_id = ?",
                String.class, eventId);
        assertThat(bookingStatus).isIn("CANCELLED_BEFORE_PAYMENT", "CANCELLED");

        // foreign organiser cannot touch our event
        UserRef stranger = createUser("USER");
        assertThat(post("/api/v1/organiser/events/" + eventId + "/publish", stranger.token(), null, null)
                .getStatusCode().value()).isEqualTo(403);
    }
}
