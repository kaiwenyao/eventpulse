package dev.kaiwen.eventpulse.catalogue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.auth.AuthUser;
import dev.kaiwen.eventpulse.booking.BookingCancellationBatch;
import dev.kaiwen.eventpulse.catalogue.CatalogueDtos.CapacityRequest;
import dev.kaiwen.eventpulse.catalogue.CatalogueDtos.CreateEventRequest;
import dev.kaiwen.eventpulse.catalogue.CatalogueDtos.FunnelRow;
import dev.kaiwen.eventpulse.catalogue.CatalogueDtos.UpdateEventRequest;
import dev.kaiwen.eventpulse.common.web.TraceIdFilter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/organiser")
@Tag(name = "organiser")
public class OrganiserEventController {

    private final OrganiserCatalogueService service;
    private final BookingCancellationBatch cancellationBatch;

    public OrganiserEventController(OrganiserCatalogueService service, BookingCancellationBatch cancellationBatch) {
        this.service = service;
        this.cancellationBatch = cancellationBatch;
    }

    @Operation(summary = "Create draft event with tiers and inventory")
    @PostMapping("/events")
    @PreAuthorize("hasRole('ORGANISER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody CreateEventRequest request) {
        return java.util.Map.of("eventId", service.createEvent(user, request).toString());
    }

    @Operation(summary = "Publish draft event")
    @PostMapping("/events/{id}/publish")
    @PreAuthorize("hasRole('ORGANISER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publish(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        service.publishEvent(user, id);
    }

    @Operation(summary = "Update draft event")
    @PutMapping("/events/{id}")
    @PreAuthorize("hasRole('ORGANISER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @RequestBody UpdateEventRequest request) {
        service.updateEvent(user, id, request);
    }

    @Operation(summary = "Cancel event: stop new bookings now, then batch-cancel orders")
    @PostMapping("/events/{id}/cancel")
    @PreAuthorize("hasRole('ORGANISER') or hasRole('ADMIN')")
    public Map<String, Object> cancel(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        service.cancelEvent(user, id, body == null ? null : body.get("reason"));
        BookingCancellationBatch.BatchResult result = cancellationBatch.runForEvent(id);
        return java.util.Map.of("cancelled", result.cancelled(), "failed", result.failed());
    }

    @Operation(summary = "Adjust tier capacity (If-Match: inventory version)")
    @PatchMapping("/tiers/{tierId}/inventory")
    @PreAuthorize("hasRole('ORGANISER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adjustCapacity(@AuthenticationPrincipal AuthUser user, @PathVariable UUID tierId,
            @RequestHeader(value = "If-Match", required = false) Long ifMatch,
            @Valid @RequestBody CapacityRequest request) {
        service.adjustCapacity(user, tierId, request.capacity(), ifMatch, TraceIdFilter.currentTraceId());
    }

    @Operation(summary = "Own events with a minimal funnel")
    @GetMapping("/funnel")
    @PreAuthorize("hasRole('ORGANISER') or hasRole('ADMIN')")
    public Map<String, Object> funnel(@AuthenticationPrincipal AuthUser user) {
        return java.util.Map.of("items", service.funnel(user));
    }
}
