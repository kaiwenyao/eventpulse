package dev.kaiwen.eventpulse;

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

    /**
     * The refresh cookie travels in the Set-Cookie header, which RestTemplate
     * exposes fine — the cookie only needs to be echoed back manually. These
     * tests cover the previously untested happy path: rotation of a never-used
     * token (the pre-fix code 500'd with an NPE on used_at IS NULL) and
     * per-family reuse detection (plan §12: 过期、重用、并发 refresh).
     */
    @Test
    void refreshRotatesCookieAndAccessToken() {
        String email = "rot-it-" + UUID.randomUUID() + "@test.dev";
        ResponseEntity<Map> registered = post("/api/v1/auth/register", null,
                Map.of("email", email, "password", "Smoke!234567890", "displayName", "Rotation User"));
        assertThat(registered.getStatusCode().value()).isEqualTo(201);

        String firstCookie = setCookieValue(registered.getHeaders(), "ep_refresh");
        assertThat(firstCookie).as("register must set the HttpOnly refresh cookie").isNotBlank();
        // §12 cookie hardening: HttpOnly + SameSite are unconditional in code;
        // Secure is deployment-controlled (off over plain-http localhost, on
        // and asserted in the prod profile).
        String setCookie = registered.getHeaders().get("Set-Cookie").stream()
                .filter(value -> value.startsWith("ep_refresh=")).findFirst().orElse("");
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax").doesNotContain("Secure");
        // The stored value is a hash; nothing resembling the raw token persists.
        Integer rawLeaks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, firstCookie);
        assertThat(rawLeaks).isZero();

        // First rotation of a never-used token must succeed (used_at IS NULL).
        ResponseEntity<Map> refreshed = exchangeWithCookie("/api/v1/auth/refresh", firstCookie);
        assertThat(refreshed.getStatusCode().value()).isEqualTo(200);
        String rotatedCookie = setCookieValue(refreshed.getHeaders(), "ep_refresh");
        assertThat(rotatedCookie).as("rotation issues a new refresh cookie").isNotBlank()
                .isNotEqualTo(firstCookie);
        assertThat(((Map<?, ?>) body(refreshed).get("user")).get("email")).isEqualTo(email);
        assertThat(get("/api/v1/auth/me", (String) body(refreshed).get("accessToken"))
                .getStatusCode().value()).isEqualTo(200);

        // Rotated cookie works (new family), old cookie is now used_at-stamped.
        ResponseEntity<Map> secondRefresh = exchangeWithCookie("/api/v1/auth/refresh", rotatedCookie);
        assertThat(secondRefresh.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void refreshReuseDetectionKillsSessions() {
        String email = "reuse-it-" + UUID.randomUUID() + "@test.dev";
        ResponseEntity<Map> registered = post("/api/v1/auth/register", null,
                Map.of("email", email, "password", "Smoke!234567890", "displayName", "Reuse User"));
        assertThat(registered.getStatusCode().value()).isEqualTo(201);
        String originalCookie = setCookieValue(registered.getHeaders(), "ep_refresh");

        ResponseEntity<Map> rotated = exchangeWithCookie("/api/v1/auth/refresh", originalCookie);
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        String rotatedAccess = (String) body(rotated).get("accessToken");

        // Replaying the ALREADY-ROTATED first cookie must kill the family: 401
        // TOKEN_REUSE_DETECTED, access tokens expired, token_version bumped.
        ResponseEntity<Map> reuse = exchangeWithCookie("/api/v1/auth/refresh", originalCookie);
        assertThat(reuse.getStatusCode().value()).isEqualTo(401);
        assertThat(body(reuse).get("code")).isEqualTo("TOKEN_REUSE_DETECTED");

        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        Integer reusedFamilies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_families WHERE user_id = ? AND status = 'REUSED'",
                Integer.class, userId);
        assertThat(reusedFamilies).as("reuse detection marks the family REUSED").isEqualTo(1);
        Integer liveAccess = jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_tokens WHERE user_id = ? AND expires_at > now()",
                Integer.class, userId);
        assertThat(liveAccess).as("reuse revokes every access token of the user").isZero();
        Integer tokenVersion = jdbc.queryForObject("SELECT token_version FROM users WHERE id = ?",
                Integer.class, userId);
        assertThat(tokenVersion).isEqualTo(1);
        // The access token minted by the rotation is revoked too.
        assertThat(get("/api/v1/auth/me", rotatedAccess).getStatusCode().value()).isEqualTo(401);
    }

    private ResponseEntity<Map> exchangeWithCookie(String path, String cookie) {
        // Raw JDK client: no cookie store, the Cookie header is explicit.
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                    java.net.URI.create("http://localhost:" + port + path).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            // The header must carry name=value, not the bare token value.
            connection.setRequestProperty("Cookie", "ep_refresh=" + cookie);
            connection.getOutputStream().write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            java.io.InputStream stream = status < 400 ? connection.getInputStream()
                    : connection.getErrorStream();
            String raw = stream == null ? "{}"
                    : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            java.util.List<String> setCookies = connection.getHeaderFields().getOrDefault(
                    "Set-Cookie", java.util.List.of());
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> parsed = dev.kaiwen.eventpulse.outbox.OutboxJson.mapper()
                    .readValue(raw, java.util.Map.class);
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            setCookies.forEach(value -> headers.add("Set-Cookie", value));
            return new ResponseEntity<>(parsed, headers,
                    org.springframework.http.HttpStatus.valueOf(status));
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String setCookieValue(org.springframework.http.HttpHeaders headers, String name) {
        return headers.get("Set-Cookie") == null ? null : headers.get("Set-Cookie").stream()
                .filter(value -> value.startsWith("ep_refresh="))
                .map(value -> value.split(";", 2)[0].substring("ep_refresh=".length()))
                .findFirst().orElse(null);
    }

    @Test
    void meRequiresAuthentication() {
        assertThat(get("/api/v1/auth/me", null).getStatusCode().value()).isEqualTo(401);
    }
}
