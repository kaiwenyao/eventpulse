package dev.kaiwen.eventpulse.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.dto.CatalogueDtos.CapacityRequest;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.CreateEventRequest;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.FunnelRow;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.UpdateEventRequest;
import dev.kaiwen.eventpulse.security.AuthUser;

/**
 * Organiser-side catalogue management.
 */
public interface OrganiserCatalogueService {

    UUID createEvent(AuthUser user, CreateEventRequest request);

    void publishEvent(AuthUser user, UUID eventId);

    void updateEvent(AuthUser user, UUID eventId, UpdateEventRequest request);

    UUID cancelEvent(AuthUser user, UUID eventId, String reason);

    void adjustCapacity(AuthUser user, UUID tierId, int newCapacity, Long expectedVersion, String traceId);

    List<FunnelRow> funnel(AuthUser user);
}