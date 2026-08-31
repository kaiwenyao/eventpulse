package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.UUID;

import dev.kaiwen.eventpulse.dto.CatalogueDtos.EventDetail;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.SearchResult;

/**
 * Catalogue read access. Search returns a stable keyset page.
 */
public interface CatalogueService {

    SearchResult search(String q, String category, String city, Long priceMin, Long priceMax,
            Instant from, Instant to, boolean availableOnly, String sort, String cursor, Integer limit);

    SearchResult nearby(double lat, double lng, double radiusKm, Integer limit);

    EventDetail detail(UUID eventId);

    String detailEtag(UUID eventId);
}