package dev.kaiwen.eventpulse.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.dto.CatalogueDtos.EventDetail;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.SearchResult;
import dev.kaiwen.eventpulse.security.AuthUser;
import dev.kaiwen.eventpulse.service.CatalogueService;
import dev.kaiwen.eventpulse.service.SavedEventService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "catalogue")
public class EventController {

    private final CatalogueService catalogue;
    private final SavedEventService savedEvents;

    public EventController(CatalogueService catalogue, SavedEventService savedEvents) {
        this.catalogue = catalogue;
        this.savedEvents = savedEvents;
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
        savedEvents.save(user.id(), eventId);
    }

    @Operation(summary = "Unsave an event")
    @DeleteMapping("/me/saved-events/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsave(@AuthenticationPrincipal AuthUser user, @PathVariable UUID eventId) {
        savedEvents.unsave(user.id(), eventId);
    }

    @GetMapping("/me/saved-events")
    public Map<String, Object> saved(@AuthenticationPrincipal AuthUser user) {
        return savedEvents.saved(user.id());
    }
}