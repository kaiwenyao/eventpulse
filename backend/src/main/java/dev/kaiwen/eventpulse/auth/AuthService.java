package dev.kaiwen.eventpulse.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.common.DbClock;
import dev.kaiwen.eventpulse.common.error.ApiException;
import dev.kaiwen.eventpulse.common.error.ErrorCode;
import dev.kaiwen.eventpulse.common.web.RateLimiter;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class AuthService {

    public record TokenResponse(String accessToken, long expiresInSeconds, UserInfo user) {
    }

    public record UserInfo(UUID id, String email, String role, String displayName) {
    }

    public record RegisterRequest(String email, String password, String displayName, PreferencesInput preferences) {
    }

    public record PreferencesInput(List<String> categories, String coarseLocation, Integer radiusKm,
                                   Long budgetMinor) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final RateLimiter rateLimiter;
    private final DbClock clock;

    public AuthService(JdbcTemplate jdbc, TransactionTemplate tx, PasswordEncoder passwordEncoder,
            AppProperties properties, RateLimiter rateLimiter, DbClock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public TokenResponse register(RegisterRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        rateLimiter.check("register", clientKey(httpRequest));
        validateEmail(request.email());
        validatePassword(request.password());
        String passwordHash = passwordEncoder.encode(request.password());
        return tx.execute(status -> {
            UUID userId;
            try {
                userId = jdbc.queryForObject("""
                        INSERT INTO users (email, password_hash, display_name, role)
                        VALUES (?, ?, ?, 'USER') RETURNING id
                        """, UUID.class, request.email().trim().toLowerCase(), passwordHash,
                        request.displayName());
            }
            catch (DuplicateKeyException e) {
                throw new ApiException(ErrorCode.CONFLICT, "email already registered",
                        Map.of("email", "already registered"));
            }
            upsertPreferences(userId, request.preferences());
            issueRefresh(userId, response);
            return issueAccess(userId);
        });
    }

    public TokenResponse login(LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        String key = request.email() == null ? "unknown" : request.email().trim().toLowerCase();
        rateLimiter.check("login", key);
        List<UUID> ids = jdbc.queryForList("SELECT id FROM users WHERE lower(email) = ?", UUID.class,
                key);
        if (ids.isEmpty()) {
            // Same error and timing shape as a wrong password to resist enumeration.
            try {
                passwordEncoder.matches("dummy", "$argon2id$v=19$m=19456,t=2,p=1$"
                        + "c29tZXNhbHQ$dGVzdGR1bW15aGFzaHZhbHVl");
            }
            catch (Exception ignoredHashShape) {
                // timing only
            }
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "invalid credentials");
        }
        UUID userId = ids.getFirst();
        record Cred(String hash, String status) {
        }
        List<Cred> creds = jdbc.query("SELECT password_hash, status FROM users WHERE id = ?", (rs, i) ->
                new Cred(rs.getString(1), rs.getString(2)), userId);
        if (creds.isEmpty() || !"ACTIVE".equals(creds.getFirst().status())
                || !passwordEncoder.matches(request.password(), creds.getFirst().hash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "invalid credentials");
        }
        return tx.execute(status -> {
            issueRefresh(userId, response);
            return issueAccess(userId);
        });
    }

    public TokenResponse refresh(RefreshRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        String candidate = request == null ? null : request.refreshToken();
        if (candidate == null || candidate.isBlank()) {
            candidate = refreshCookieValue(httpRequest);
        }
        if (candidate == null || candidate.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "refresh token missing");
        }
        final String raw = candidate;
        String tokenHash = CanonicalJson.sha256Hex(raw);
        return tx.execute(status -> {
            record Tok(UUID id, UUID familyId, UUID userId, Instant expiresAt, Instant usedAt, String familyStatus) {
            }
            List<Tok> found = jdbc.query("""
                    SELECT t.id, t.family_id, f.user_id, t.expires_at, t.used_at, f.status AS family_status
                    FROM refresh_tokens t JOIN refresh_families f ON f.id = t.family_id
                    WHERE t.token_hash = ?
                    """, (rs, i) -> new Tok(rs.getObject("id", UUID.class), rs.getObject("family_id", UUID.class),
                    rs.getObject("user_id", UUID.class), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("used_at", OffsetDateTime.class).toInstant(), rs.getString("family_status")), tokenHash);
            if (found.isEmpty()) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "refresh token invalid");
            }
            Tok token = found.getFirst();
            boolean reuse = token.usedAt() != null || !"ACTIVE".equals(token.familyStatus());
            if (reuse || token.expiresAt().isBefore(clock.now())) {
                // Reuse of an already-rotated token kills the whole family and all sessions.
                jdbc.update("UPDATE refresh_families SET status = 'REUSED' WHERE id = ?", token.familyId());
                jdbc.update("UPDATE access_tokens SET expires_at = now() WHERE user_id = ?", token.userId());
                jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", token.userId());
                clearCookie(response);
                throw new ApiException(ErrorCode.TOKEN_REUSE_DETECTED, "refresh token reuse detected; sessions revoked");
            }
            jdbc.update("UPDATE refresh_tokens SET used_at = now() WHERE id = ?", token.id());
            issueRefresh(token.userId(), response);
            return issueAccess(token.userId());
        });
    }

    public void logout(RefreshRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
        String candidate = request == null ? null : request.refreshToken();
        if (candidate == null || candidate.isBlank()) {
            candidate = refreshCookieValue(httpRequest);
        }
        final String raw = candidate;
        if (raw != null && !raw.isBlank()) {
            tx.executeWithoutResult(status -> jdbc.update("""
                    UPDATE refresh_families SET status = 'ROTATED'
                    WHERE id = (SELECT family_id FROM refresh_tokens WHERE token_hash = ?)
                    """, CanonicalJson.sha256Hex(raw)));
        }
        clearCookie(response);
    }

    /** Fresh re-authentication check for admin actions. */
    public boolean verifyPassword(UUID userId, String password) {
        if (password == null || password.isBlank()) {
            return false;
        }
        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM users WHERE id = ? AND status = 'ACTIVE'",
                String.class, userId);
        return !hashes.isEmpty() && passwordEncoder.matches(password, hashes.getFirst());
    }

    public UserInfo me(UUID userId) {
        return jdbc.query("""
                SELECT id, email, role, display_name FROM users WHERE id = ?
                """, (rs, i) -> new UserInfo(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("role"), rs.getString("display_name")), userId).stream().findFirst()
                .orElseThrow(ApiException::notFound);
    }

    public Map<String, Object> preferences(UUID userId) {
        // PG text[]/jsonb must be converted to JSON-safe values before serialization.
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT categories, coarse_location, radius_km, budget_minor, time_windows::text AS time_windows
                FROM user_preferences WHERE user_id = ?
                """, (rs, i) -> {
            java.sql.Array categories = rs.getArray("categories");
            List<String> categoryList = categories == null ? List.of()
                    : List.of((String[]) categories.getArray());
            return Map.<String, Object>of(
                    "categories", categoryList,
                    "coarseLocation", rs.getString("coarse_location") == null ? "" : rs.getString("coarse_location"),
                    "radiusKm", rs.getObject("radius_km", Integer.class) == null ? 0
                            : rs.getObject("radius_km", Integer.class),
                    "budgetMinor", rs.getObject("budget_minor", Long.class) == null ? 0
                            : rs.getObject("budget_minor", Long.class),
                    "timeWindows", rs.getString("time_windows") == null ? "{}" : rs.getString("time_windows"));
        }, userId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    public void updatePreferences(UUID userId, PreferencesInput input) {
        tx.executeWithoutResult(status -> upsertPreferences(userId, input));
    }

    private void upsertPreferences(UUID userId, PreferencesInput input) {
        if (input == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO user_preferences (user_id, categories, coarse_location, radius_km, budget_minor)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                  categories = EXCLUDED.categories,
                  coarse_location = EXCLUDED.coarse_location,
                  radius_km = EXCLUDED.radius_km,
                  budget_minor = EXCLUDED.budget_minor,
                  version = user_preferences.version + 1
                """, userId,
                input.categories() == null ? new String[0] : input.categories().toArray(new String[0]),
                input.coarseLocation(), input.radiusKm(), input.budgetMinor());
    }

    private TokenResponse issueAccess(UUID userId) {
        String raw = CanonicalJson.newOpaqueToken();
        Duration ttl = properties.security().accessTokenTtl();
        jdbc.update("INSERT INTO access_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                userId, CanonicalJson.sha256Hex(raw), java.sql.Timestamp.from(Instant.now().plus(ttl)));
        UserInfo user = jdbc.query("""
                SELECT id, email, role, display_name FROM users WHERE id = ?
                """, (rs, i) -> new UserInfo(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("role"), rs.getString("display_name")), userId).getFirst();
        return new TokenResponse(raw, ttl.toSeconds(), user);
    }

    private void issueRefresh(UUID userId, HttpServletResponse response) {
        String raw = CanonicalJson.newOpaqueToken();
        Duration ttl = properties.security().refreshTokenTtl();
        jdbc.update("""
                WITH family AS (
                  INSERT INTO refresh_families (user_id) VALUES (?) RETURNING id
                )
                INSERT INTO refresh_tokens (family_id, token_hash, expires_at)
                SELECT family.id, ?, ? FROM family
                """, userId, CanonicalJson.sha256Hex(raw), java.sql.Timestamp.from(Instant.now().plus(ttl)));
        Cookie cookie = new Cookie("ep_refresh", raw);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // set true behind TLS
        cookie.setAttribute("SameSite", "Lax");
        cookie.setPath("/api/v1/auth");
        cookie.setMaxAge((int) ttl.toSeconds());
        response.addCookie(cookie);
    }

    private String refreshCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if ("ep_refresh".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void clearCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie("ep_refresh", "");
        cookie.setHttpOnly(true);
        cookie.setPath("/api/v1/auth");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private void validateEmail(String email) {
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid request",
                    Map.of("email", "must be a valid email address"));
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 10 || password.length() > 200) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid request",
                    Map.of("password", "must be 10..200 characters"));
        }
    }

    private String clientKey(HttpServletRequest request) {
        return request.getHeader("X-Forwarded-For") == null
                ? "local"
                : request.getHeader("X-Forwarded-For").split(",")[0].trim();
    }
}
