package dev.kaiwen.eventpulse.seed;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.service.EmbeddingService;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Demo seed (profile 'demo' only). Idempotent: no-ops when users exist.
 * Creates an admin, a normal user and an organiser with published events
 * across categories so the demo and smoke tests have stable data.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final EmbeddingService embeddingService;

    public DemoDataSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, EmbeddingService embeddingService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.embeddingService = embeddingService;
    }

    @Override
    public void run(String... args) {
        // Per-entity idempotency: an interrupted previous pass (e.g. a crash
        // mid-seed) must not leave the demo half-forever-empty.
        jdbc.update("""
                INSERT INTO users (email, password_hash, role, display_name)
                VALUES ('admin@eventpulse.dev', ?, 'ADMIN', 'Platform Admin')
                ON CONFLICT DO NOTHING
                """, passwordEncoder.encode("Admin!234567890"));
        jdbc.update("""
                INSERT INTO users (email, password_hash, role, display_name)
                VALUES ('user@eventpulse.dev', ?, 'USER', 'Demo User')
                ON CONFLICT DO NOTHING
                """, passwordEncoder.encode("User!234567890"));

        UUID organiserUserId = ensureUser("organiser@eventpulse.dev", "Organiser!234567890", "ORGANISER",
                "Live House Organiser");
        UUID organiserId = ensureOrganiser(organiserUserId);
        Integer eventCount = jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE organiser_id = ?",
                Integer.class, organiserId);
        if (eventCount == null || eventCount == 0) {
            seedEvents(organiserId);
        }
    }

    private UUID ensureUser(String email, String password, String role, String displayName) {
        jdbc.update("""
                INSERT INTO users (email, password_hash, role, display_name)
                VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, email, passwordEncoder.encode(password), role, displayName);
        return jdbc.queryForObject("SELECT id FROM users WHERE lower(email) = ?", UUID.class, email);
    }

    private UUID ensureOrganiser(UUID organiserUserId) {
        List<UUID> existing = jdbc.queryForList("SELECT id FROM organisers WHERE owner_user_id = ? LIMIT 1",
                UUID.class, organiserUserId);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        return jdbc.queryForObject("""
                INSERT INTO organisers (owner_user_id, name, status) VALUES (?, '脉搏现场工作室', 'ACTIVE')
                RETURNING id
                """, UUID.class, organiserUserId);
    }

    private void seedEvents(UUID organiserId) {

        seedEvent(organiserId, "城市脉搏 · 独立摇滚之夜", "本地三支乐队联合专场，感受城市的音乐脉搏。", "music",
                "2026-09-18", 20, 22, "上海", "创新公园现场", 31.2304, 121.4737,
                tier("普通票", 18000, 300), tier("VIP票", 38000, 50));
        seedEvent(organiserId, "AI 与城市生活 · 技术沙龙", "从推荐系统到智能体，聊聊 AI 如何改变城市日常。", "tech",
                "2026-09-25", 14, 17, "上海", "科创沙龙空间", 31.2290, 121.4690,
                tier("早鸟票", 4900, 120), tier("现场票", 9900, 80));
        seedEvent(organiserId, "滨江晨跑 5K", "周末清晨，沿滨江跑道轻松慢跑，欢迎所有配速。", "sports",
                "2026-10-11", 7, 9, "上海", "滨江跑道北段", 31.2200, 121.5000,
                tier("免费票", 0, 200));
        seedEvent(organiserId, "城市光影 · 数字艺术展", "沉浸式数字艺术展，探索光与城市的边界。", "art",
                "2026-10-24", 10, 18, "北京", "798光影中心", 39.9847, 116.4960,
                tier("平日票", 8800, 500), tier("双人票", 15800, 100));
    }

    private void seedEvent(UUID organiserId, String title, String description, String category, String date,
            int startHour, int endHour, String city, String venueName, double lat, double lng,
            String[]... tiers) {
        UUID venueId = jdbc.queryForObject("""
                INSERT INTO venues (name, address, city, country, location, timezone)
                VALUES (?, ?, ?, 'CN', ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 'Asia/Shanghai')
                RETURNING id
                """, UUID.class, venueName, venueName + " 地址", city, lng, lat);
        UUID eventId = jdbc.queryForObject("""
                INSERT INTO events (organiser_id, venue_id, title, description, category, status, starts_at,
                                    ends_at, policy)
                VALUES (?, ?, ?, ?, ?, 'PUBLISHED',
                        ?::timestamptz, ?::timestamptz,
                        '{"cancellable": true, "cancellationDeadlineHoursBeforeStart": 24, "resaleAllowed": false, "version": 1}'::jsonb)
                RETURNING id
                """, UUID.class, organiserId, venueId, title, description, category,
                java.sql.Timestamp.from(
                        LocalDate.parse(date).atTime(startHour, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()),
                java.sql.Timestamp.from(
                        LocalDate.parse(date).atTime(endHour, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        embeddingService.embedEvent(eventId, title, category, description);
        int sequence = 0;
        for (String[] t : tiers) {
            sequence++;
            UUID tierId = jdbc.queryForObject("""
                    INSERT INTO ticket_tiers (event_id, name, currency, unit_price_minor, sale_start_at,
                                              sale_end_at, per_user_limit, status)
                    VALUES (?, ?, 'CNY', ?::bigint, now() - interval '1 day', now() + interval '30 days', 10,
                            'ACTIVE')
                    RETURNING id
                    """, UUID.class, eventId, t[0], t[1]);
            jdbc.update("""
                    INSERT INTO inventory (tier_id, capacity, available) VALUES (?, ?::int, ?::int)
                    """, tierId, t[2], t[2]);
        }
        jdbc.update("""
                INSERT INTO interactions (request_id, event_id, type)
                VALUES (?, ?, 'VIEW')
                """, "seed-" + CanonicalJson.newIdempotencyKeyHint(), eventId);
    }

    private String[] tier(String name, long priceMinor, int capacity) {
        return new String[] { name, String.valueOf(priceMinor), String.valueOf(capacity) };
    }
}
