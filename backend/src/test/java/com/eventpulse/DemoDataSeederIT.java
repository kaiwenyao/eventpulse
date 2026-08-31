package com.eventpulse;

import java.util.Map;
import java.util.UUID;

import com.eventpulse.recs.EmbeddingService;
import com.eventpulse.seed.DemoDataSeeder;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo seeder: full seed pass on the test database, idempotent on re-run
 * (including after an interrupted pass that only created users).
 */
class DemoDataSeederIT extends IntegrationTestBase {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmbeddingService embeddingService;

    @Test
    void seedsIdempotentlyAndCompletesPartiallySeededDatabases() {
        DemoDataSeeder seeder = new DemoDataSeeder(jdbc, passwordEncoder, embeddingService);
        seeder.run();

        Integer adminUsers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = 'admin@eventpulse.dev'", Integer.class);
        Integer organisers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM organisers WHERE name = '脉搏现场工作室'", Integer.class);
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE organiser_id = (SELECT id FROM organisers LIMIT 1)",
                Integer.class);
        assertThat(adminUsers).isEqualTo(1);
        assertThat(organisers).isEqualTo(1);
        assertThat(events).isGreaterThanOrEqualTo(4);
        Integer tiers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_tiers t JOIN events e ON e.id = t.event_id "
                        + "WHERE e.title LIKE '城市%' OR e.title LIKE 'AI %' OR e.title LIKE '滨江%'",
                Integer.class);
        assertThat(tiers).isGreaterThanOrEqualTo(4);

        // second run is a no-op
        seeder.run();
        Integer eventsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE organiser_id = (SELECT id FROM organisers LIMIT 1)",
                Integer.class);
        assertThat(eventsAfter).isEqualTo(events);

        // a database where users exist but events were never seeded still gets its events
        UUID organiserUserId = jdbc.queryForObject(
                "SELECT owner_user_id FROM organisers WHERE name = '脉搏现场工作室' LIMIT 1", UUID.class);
        jdbc.update("DELETE FROM interactions WHERE event_id IN (SELECT id FROM events WHERE organiser_id = ?)",
                organiserUserId);
        jdbc.update("DELETE FROM events WHERE organiser_id = ?", organiserUserId);
        seeder.run();
        Integer eventsRestored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE organiser_id = ?", Integer.class, organiserUserId);
        assertThat(eventsRestored).isGreaterThanOrEqualTo(4);
    }

    @Test
    void embeddingServiceWritesVectorsWhenPgvectorPresent() {
        // the test image ships pgvector, so the column exists
        assertThat(embeddingService.isVectorAvailable()).isTrue();
        UUID eventId = jdbc.queryForObject("SELECT id FROM events LIMIT 1", UUID.class);
        embeddingService.embedEvent(eventId, "摇滚 音乐 concert", "music", "描述 description");
        String vector = jdbc.queryForObject("SELECT embedding::text FROM events WHERE id = ?", String.class,
                eventId);
        assertThat(vector).startsWith("[").endsWith("]");
        // unit vector: length 64 components
        assertThat(vector.split(",")).hasSize(64);
    }
}
