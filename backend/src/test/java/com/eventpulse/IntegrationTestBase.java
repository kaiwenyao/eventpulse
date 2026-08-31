package com.eventpulse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.lifecycle.TestLifecycleAware;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.eventpulse.common.CanonicalJson;

/**
 * Shared integration-test base: a real PostgreSQL/PostGIS container, a random
 * HTTP port and JDBC fixtures that bypass slow registration flows. Schedulers
 * are stretched to an hour so tests drive ticks deterministically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    /**
     * JVM-wide shared container. Started in a static block (not JUnit
     * {@code @Container}) so the Spring context cache stays valid across test
     * classes: a per-class container would be stopped after the first class
     * while later classes reuse the cached datasource URL.
     */
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("eventpulse/postgres:18-3.6-pgvector")
                            .asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    @org.springframework.test.context.DynamicPropertySource
    static void datasourceProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    protected RestTemplate rest = errorCollectingRestTemplate();

    @org.springframework.boot.test.web.server.LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * RestTemplate that surfaces 4xx/5xx as ResponseEntity instead of
     * throwing, so tests can assert status codes.
     */
    static RestTemplate errorCollectingRestTemplate() {
        RestTemplate template = new RestTemplate(
                new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
        template.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return template;
    }

    // ------------------------------------------------------------- HTTP utils

    protected ResponseEntity<Map> exchange(String method, String path, String token, Object body,
            Map<String, String> extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (extraHeaders != null) {
            extraHeaders.forEach(headers::set);
        }
        return rest.exchange("http://localhost:" + port + path, HttpMethod.valueOf(method),
                new HttpEntity<>(body, headers), Map.class);
    }

    protected ResponseEntity<Map> post(String path, String token, Object body) {
        return exchange("POST", path, token, body, null);
    }

    protected ResponseEntity<Map> post(String path, String token, Object body, String idempotencyKey) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Idempotency-Key", idempotencyKey);
        return exchange("POST", path, token, body, headers);
    }

    protected ResponseEntity<Map> get(String path, String token) {
        return exchange("GET", path, token, null, null);
    }

    protected Map<String, Object> body(ResponseEntity<Map> response) {
        return response.getBody() == null ? Map.of() : response.getBody();
    }

    // --------------------------------------------------------------- fixtures

    public record UserRef(UUID id, String token) {
    }

    protected UserRef createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, ?)
                """, id, "u-" + id + "@test.dev", "x-not-a-real-hash", role);
        String token = CanonicalJson.newOpaqueToken();
        jdbc.update("""
                INSERT INTO access_tokens (user_id, token_hash, expires_at) VALUES (?, ?, now() + interval '2 hours')
                """, id, sha256(token));
        return new UserRef(id, token);
    }

    public record OrganiserRef(UUID organiserId, UUID eventId, UUID tierId) {
    }

    /**
     * A published event with one ACTIVE tier of the given capacity and
     * per-user limit, sale window spanning now.
     */
    protected OrganiserRef createEventWithTier(int capacity, int perUserLimit) {
        return createEventWithTier("CNY", 10000L, capacity, perUserLimit);
    }

    protected OrganiserRef createEventWithTier(String currency, long unitPriceMinor, int capacity,
            int perUserLimit) {
        UUID organiserUserId = createUser("ORGANISER").id();
        UUID organiserId = jdbc.queryForObject("""
                INSERT INTO organisers (owner_user_id, name, status) VALUES (?, ?, 'ACTIVE') RETURNING id
                """, UUID.class, organiserUserId, "Test Organiser");
        UUID venueId = jdbc.queryForObject("""
                INSERT INTO venues (name, city, location) VALUES (?, '上海',
                  ST_SetSRID(ST_MakePoint(121.47, 31.23), 4326)::geography)
                RETURNING id
                """, UUID.class, "Test Venue");
        UUID eventId = jdbc.queryForObject("""
                INSERT INTO events (organiser_id, venue_id, title, category, status, starts_at, ends_at, policy)
                VALUES (?, ?, 'Test Event', 'music', 'PUBLISHED',
                        now() - interval '1 hour', now() + interval '6 hours',
                        '{"cancellable": true, "cancellationDeadlineHoursBeforeStart": 0, "resaleAllowed": false, "version": 1}'::jsonb)
                RETURNING id
                """, UUID.class, organiserId, venueId);
        UUID tierId = jdbc.queryForObject("""
                INSERT INTO ticket_tiers (event_id, name, currency, unit_price_minor, sale_start_at,
                                          sale_end_at, per_user_limit, status)
                VALUES (?, '标准票', ?, ?, now() - interval '1 hour', now() + interval '6 days', ?, 'ACTIVE')
                RETURNING id
                """, UUID.class, eventId, currency, unitPriceMinor, perUserLimit);
        jdbc.update("INSERT INTO inventory (tier_id, capacity, available) VALUES (?, ?, ?)", tierId, capacity,
                capacity);
        return new OrganiserRef(organiserId, eventId, tierId);
    }

    // ------------------------------------------------------------- assertions

    protected Map<String, Object> inventoryRow(UUID tierId) {
        return jdbc.queryForMap("SELECT capacity, available, reserved, sold, withheld FROM inventory "
                + "WHERE tier_id = ?", tierId);
    }

    protected void assertInventoryInvariant(UUID tierId) {
        Map<String, Object> inv = inventoryRow(tierId);
        long sum = ((Number) inv.get("available")).longValue() + ((Number) inv.get("reserved")).longValue()
                + ((Number) inv.get("sold")).longValue() + ((Number) inv.get("withheld")).longValue();
        org.assertj.core.api.Assertions.assertThat(sum)
                .as("available+reserved+sold+withheld == capacity for tier " + tierId)
                .isEqualTo(((Number) inv.get("capacity")).longValue());
        List<String> negatives = List.of("available", "reserved", "sold", "withheld");
        for (String column : negatives) {
            org.assertj.core.api.Assertions.assertThat(((Number) inv.get(column)).longValue())
                    .as(column + " must be >= 0").isGreaterThanOrEqualTo(0);
        }
    }

    protected static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
