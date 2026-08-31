package dev.kaiwen.eventpulse.catalogue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.auth.AuthUser;
import dev.kaiwen.eventpulse.catalogue.CatalogueDtos.EventDetail;
import dev.kaiwen.eventpulse.catalogue.CatalogueDtos.SearchResult;
import dev.kaiwen.eventpulse.common.error.ApiException;
import dev.kaiwen.eventpulse.common.web.RateLimiter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "catalogue")
public class EventController {

    private final CatalogueService catalogue;
    private final JdbcTemplate jdbc;
    private final RateLimiter rateLimiter;

    public EventController(CatalogueService catalogue, JdbcTemplate jdbc, RateLimiter rateLimiter) {
        this.catalogue = catalogue;
        this.jdbc = jdbc;
        this.rateLimiter = rateLimiter;
    }

    @Operation(summary = "Keyset search; cursor is server-signed and carries queryAsOf")
    @GetMapping("/events")
    public SearchResult search(@RequestParam(required = false) String q,
            @RequestParam(required = false) String category, @RequestParam(required = false) String city,
            @RequestParam(required = false) Long priceMin, @RequestParam(required = false) Long priceMax,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "false") boolean availableOnly,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return catalogue.search(q, category, city, priceMin, priceMax, from, to, availableOnly, sort, cursor,
                limit);
    }

    @Operation(summary = "Radius search; radius capped at 50 km")
    @GetMapping("/events/nearby")
    public SearchResult nearby(@RequestParam double lat, @RequestParam double lng,
            @RequestParam(defaultValue = "10") double radiusKm, @RequestParam(required = false) Integer limit) {
        return catalogue.nearby(lat, lng, radiusKm, limit);
    }

    @Operation(summary = "Event detail with ETag; checkout re-validates all facts")
    @GetMapping("/events/{id}")
    public ResponseEntity<EventDetail> detail(@PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        String etag = catalogue.detailEtag(id);
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(catalogue.detail(id));
    }

    @Operation(summary = "Save (favourite) an event; idempotent")
    @PutMapping("/me/saved-events/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@AuthenticationPrincipal AuthUser user, @PathVariable UUID eventId) {
        assertPublished(eventId);
        jdbc.update("""
                INSERT INTO saved_events (user_id, event_id) VALUES (?, ?)
                ON CONFLICT (user_id, event_id) DO NOTHING
                """, user.id(), eventId);
        recordInteraction(user, eventId, "SAVE", null);
    }

    @Operation(summary = "Unsave an event")
    @DeleteMapping("/me/saved-events/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsave(@AuthenticationPrincipal AuthUser user, @PathVariable UUID eventId) {
        int rows = jdbc.update("DELETE FROM saved_events WHERE user_id = ? AND event_id = ?", user.id(),
                eventId);
        if (rows == 1) {
            recordInteraction(user, eventId, "UNSAVE", null);
        }
    }

    @GetMapping("/me/saved-events")
    public Map<String, Object> saved(@AuthenticationPrincipal AuthUser user) {
        List<UUID> ids = jdbc.queryForList("SELECT event_id FROM saved_events WHERE user_id = ? "
                + "ORDER BY saved_at DESC", UUID.class, user.id());
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (UUID id : ids) {
            try {
                EventDetail d = catalogue.detail(id);
                items.add(Map.of("id", d.id().toString(), "title", d.title(), "startsAt", d.startsAt().toString(),
                        "city", d.city() == null ? "" : d.city()));
            }
            catch (ApiException hidden) {
                // event hidden or removed: drop from the list
            }
        }
        return Map.of("items", items);
    }

    private void assertPublished(UUID eventId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE id = ? AND status = 'PUBLISHED'",
                Integer.class, eventId);
        if (count == null || count == 0) {
            throw ApiException.notFound();
        }
    }

    private void recordInteraction(AuthUser user, UUID eventId, String type, Integer position) {
        String dedupeKey = UUID.nameUUIDFromBytes(
                (user.id() + "-" + eventId + "-" + type).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        jdbc.update("""
                INSERT INTO interactions (request_id, user_id, event_id, type, position)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, dedupeKey, user.id(), eventId, type, position);
    }
}
