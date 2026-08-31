package dev.kaiwen.eventpulse.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.dto.CatalogueDtos.EventDetail;
import dev.kaiwen.eventpulse.exception.ApiException;
import dev.kaiwen.eventpulse.service.CatalogueService;
import dev.kaiwen.eventpulse.service.SavedEventService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SavedEventServiceImpl implements SavedEventService {

    private final JdbcTemplate jdbc;
    private final CatalogueService catalogue;

    public SavedEventServiceImpl(JdbcTemplate jdbc, CatalogueService catalogue) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
    }

    @Override
    public void save(UUID userId, UUID eventId) {
        assertPublished(eventId);
        jdbc.update("""
                INSERT INTO saved_events (user_id, event_id) VALUES (?, ?)
                ON CONFLICT (user_id, event_id) DO NOTHING
                """, userId, eventId);
        recordInteraction(userId, eventId, "SAVE", null);
    }

    @Override
    public void unsave(UUID userId, UUID eventId) {
        int rows = jdbc.update("DELETE FROM saved_events WHERE user_id = ? AND event_id = ?", userId,
                eventId);
        if (rows == 1) {
            recordInteraction(userId, eventId, "UNSAVE", null);
        }
    }

    @Override
    public Map<String, Object> saved(UUID userId) {
        List<UUID> ids = jdbc.queryForList("SELECT event_id FROM saved_events WHERE user_id = ? "
                + "ORDER BY saved_at DESC", UUID.class, userId);
        List<Map<String, Object>> items = new ArrayList<>();
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

    private void recordInteraction(UUID userId, UUID eventId, String type, Integer position) {
        String dedupeKey = UUID.nameUUIDFromBytes(
                (userId + "-" + eventId + "-" + type).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        jdbc.update("""
                INSERT INTO interactions (request_id, user_id, event_id, type, position)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, dedupeKey, userId, eventId, type, position);
    }
}