package dev.kaiwen.eventpulse.catalogue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catalogue read/write access. Search returns a stable keyset page; every
 * mutating query records the organiser-owned rows it touched.
 */
public final class CatalogueDtos {

    public record EventListItem(UUID id, String title, String category, String status, Instant startsAt,
                                Instant endsAt, String venueName, String city, Double lat, Double lng,
                                Long minPriceMinor, String currency, Long available, String imageUrl) {
    }

    public record SearchResult(List<EventListItem> items, String nextCursor, Instant queryAsOf) {
    }

    public record TierView(UUID id, String name, Long unitPriceMinor, String currency, Instant saleStartAt,
                           Instant saleEndAt, Integer perUserLimit, String status, Integer capacity,
                           Integer available, Integer sold) {
    }

    public record EventDetail(UUID id, String title, String description, String category, String status,
                              Instant startsAt, Instant endsAt, Integer ageRequirement, Integer policyVersion,
                              Map<String, Object> policy, String venueName, String city, Double lat, Double lng,
                              String timezone, String organiserName, List<TierView> tiers, Instant updatedAt) {
    }

    public record CreateEventRequest(String title, String description, String category, Instant startsAt,
                                     Instant endsAt, Integer ageRequirement, Map<String, Object> policy,
                                     VenueInput venue, List<TierInput> tiers) {
    }

    public record VenueInput(String name, String address, String city, String country, Double lat, Double lng,
                             String timezone) {
    }

    public record TierInput(String name, String currency, Long unitPriceMinor, Instant saleStartAt,
                            Instant saleEndAt, Integer perUserLimit, Integer capacity) {
    }

    public record UpdateEventRequest(String title, String description, String category, Instant startsAt,
                                     Instant endsAt, Integer ageRequirement, Map<String, Object> policy) {
    }

    public record CapacityRequest(Integer capacity) {
    }

    public record FunnelRow(UUID eventId, String title, String status, Instant startsAt, long views, long saves,
                            long bookingsCreated, long bookingsConfirmed, long ticketsIssued) {
    }

    private CatalogueDtos() {
    }
}
