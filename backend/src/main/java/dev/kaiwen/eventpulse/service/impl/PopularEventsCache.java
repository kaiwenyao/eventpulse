package dev.kaiwen.eventpulse.service.impl;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cached popular list that recommendation serving degrades to when the
 * concurrency bulkhead is saturated or the search pool times out (plan
 * §10.3: "失败降级至缓存热门榜单"). Refresh runs on the read-only search pool
 * with a short statement timeout; a failed refresh keeps the previous
 * snapshot and never propagates. Entries carry their display fields so the
 * degraded page can render without touching the database at all.
 */
@Component
public class PopularEventsCache {

    /** Reason code served with degraded popular-list pages. */
    public static final String FALLBACK_REASON = "POPULAR_FALLBACK";

    /** Display-ready catalog snapshot of one popular event. */
    public record CachedEvent(UUID id, String title, String category, java.time.Instant startsAt,
                              String city, long popularity) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PopularEventsCache.class);

    private final JdbcTemplate searchJdbc;
    private final long staleMs;

    private volatile List<CachedEvent> cache = List.of();
    private volatile long lastRefreshMs = Long.MIN_VALUE;

    public PopularEventsCache(@Qualifier("searchJdbcTemplate") JdbcTemplate searchJdbc,
            @org.springframework.beans.factory.annotation.Value(
                    "${eventpulse.recommendation.cache-refresh-ms:60000}") long refreshIntervalMs) {
        this.searchJdbc = searchJdbc;
        this.staleMs = Math.max(refreshIntervalMs, 10_000L);
    }

    /** Periodic refresh on the scheduler; failures keep the stale snapshot. */
    @Scheduled(fixedDelayString = "${eventpulse.recommendation.cache-refresh-ms:60000}")
    public void scheduledRefresh() {
        refresh();
    }

    /** The current snapshot, refreshed inline only when empty or stale. */
    List<CachedEvent> popular() {
        List<CachedEvent> snapshot = this.cache;
        if (snapshot.isEmpty() || System.currentTimeMillis() - lastRefreshMs > staleMs) {
            refresh();
            snapshot = this.cache;
        }
        return snapshot;
    }

    private void refresh() {
        try {
            List<CachedEvent> fresh = searchJdbc.query("""
                    SELECT e.id, e.title, e.category, e.starts_at, v.city,
                           (SELECT COUNT(*) FROM interactions i WHERE i.event_id = e.id
                              AND i.type IN ('VIEW', 'IMPRESSION')) AS popularity
                    FROM events e LEFT JOIN venues v ON v.id = e.venue_id
                    WHERE e.status = 'PUBLISHED' AND e.ends_at > now()
                    ORDER BY popularity DESC, e.starts_at ASC
                    LIMIT 30
                    """, (rs, i) -> new CachedEvent(rs.getObject("id", UUID.class), rs.getString("title"),
                    rs.getString("category"),
                    rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                    rs.getString("city"), rs.getLong("popularity")));
            if (!fresh.isEmpty()) {
                this.cache = fresh;
                this.lastRefreshMs = System.currentTimeMillis();
            }
            LOG.debug("popular-events cache refreshed ({} entries)", fresh.size());
        }
        catch (Exception e) {
            // The cache IS the degrade path: on any failure keep the snapshot.
            LOG.warn("popular-events cache refresh failed, serving stale cache: {}", e.getMessage());
        }
    }
}