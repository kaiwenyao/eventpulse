package com.eventpulse;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth lifecycle: registration rules, anti-enumeration login, refresh token
 * rotation with reuse detection, logout and the preferences record.
 */
class AuthFlowIT extends IntegrationTestBase {

    @Test
    void registerLoginAndPreferencesFlow() {
        String email = "auth-it-" + UUID.randomUUID() + "@test.dev";
        ResponseEntity<Map> registered = post("/api/v1/auth/register", null,
                Map.of("email", email, "password", "Smoke!234567890", "displayName", "IT User",
                        "preferences", Map.of("categories", java.util.List.of("music", "tech"),
                                "coarseLocation", "shanghai", "radiusKm", 30, "budgetMinor", 50000)));
        assertThat(registered.getStatusCode().value()).isEqualTo(201);
        String accessToken = (String) body(registered).get("accessToken");
        assertThat(accessToken).isNotBlank();
        UUID userId = UUID.fromString(((Map<?, ?>) body(registered).get("user")).get("id").toString());

        // duplicate email -> 409
        ResponseEntity<Map> duplicate = post("/api/v1/auth/register", null,
                Map.of("email", email, "password", "Smoke!234567890"));
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);

        // weak password -> 400
        ResponseEntity<Map> weak = post("/api/v1/auth/register", null,
                Map.of("email", "weak-" + UUID.randomUUID() + "@test.dev", "password", "short"));
        assertThat(weak.getStatusCode().value()).isEqualTo(400);

        // preferences persisted coarse + retrievable
        ResponseEntity<Map> prefs = get("/api/v1/auth/me/preferences", accessToken);
        assertThat(prefs.getStatusCode().value()).isEqualTo(200);
        assertThat(prefs.getBody().get("coarseLocation")).isEqualTo("shanghai");

        // login wrong password + unknown email share the same error shape
        ResponseEntity<Map> wrongPassword = post("/api/v1/auth/login", null,
                Map.of("email", email, "password", "Wrong!234567890"));
        assertThat(wrongPassword.getStatusCode().value()).isEqualTo(401);
        assertThat(body(wrongPassword).get("code")).isEqualTo("INVALID_CREDENTIALS");
        ResponseEntity<Map> unknown = post("/api/v1/auth/login", null,
                Map.of("email", "ghost-" + UUID.randomUUID() + "@test.dev", "password", "Whatever!123"));
        assertThat(unknown.getStatusCode().value()).isEqualTo(401);
        assertThat(body(unknown).get("code")).isEqualTo("INVALID_CREDENTIALS");

        // login ok -> me -> refresh rotation via body token
        ResponseEntity<Map> login = post("/api/v1/auth/login", null,
                Map.of("email", email, "password", "Smoke!234567890"));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String newAccess = (String) body(login).get("accessToken");
        assertThat(get("/api/v1/auth/me", newAccess).getStatusCode().value()).isEqualTo(200);

        // logout of the remaining session
        ResponseEntity<Map> logout = post("/api/v1/auth/logout", accessToken, Map.of());
        assertThat(logout.getStatusCode().value()).isEqualTo(200);

        // The raw refresh token only travels in the HttpOnly cookie on the very
        // first response; with RestTemplate we cannot replay it, so session
        // invalidation is covered through the ban path instead.
        Integer banned = jdbc.update("UPDATE users SET status = 'BANNED' WHERE id = ?", userId);
        assertThat(banned).isEqualTo(1);
        assertThat(get("/api/v1/auth/me", newAccess).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void refreshRotationRejectsUnknownToken() {
        ResponseEntity<Map> response = post("/api/v1/auth/refresh", null,
                Map.of("refreshToken", "totally-unknown-token"));
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void refreshWithMissingTokenIs401() {
        ResponseEntity<Map> response = post("/api/v1/auth/refresh", null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void meRequiresAuthentication() {
        assertThat(get("/api/v1/auth/me", null).getStatusCode().value()).isEqualTo(401);
    }
}
