package dev.kaiwen.eventpulse.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.common.DbClock;
import dev.kaiwen.eventpulse.common.web.RateLimiter;
import dev.kaiwen.eventpulse.exception.ApiException;
import dev.kaiwen.eventpulse.exception.ErrorCode;
import dev.kaiwen.eventpulse.service.AuthService;
import dev.kaiwen.eventpulse.service.AuthService.LoginRequest;
import dev.kaiwen.eventpulse.service.AuthService.PreferencesInput;
import dev.kaiwen.eventpulse.service.AuthService.RefreshRequest;
import dev.kaiwen.eventpulse.service.AuthService.RegisterRequest;
import dev.kaiwen.eventpulse.service.AuthService.TokenResponse;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class AuthServiceImpl implements AuthService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    /**
     * Revocations (reuse detection) must survive the error response, but the
     * enclosing refresh transaction rolls back on the thrown ApiException, so
     * they run in their own committed transaction (REQUIRES_NEW) first.
     */
    private final TransactionTemplate revocations;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final RateLimiter rateLimiter;
    private final DbClock clock;

    public AuthServiceImpl(JdbcTemplate jdbc, TransactionTemplate tx, PasswordEncoder passwordEncoder,
            org.springframework.transaction.PlatformTransactionManager transactionManager, AppProperties properties,
            RateLimiter rateLimiter, DbClock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.revocations = new TransactionTemplate(transactionManager);
        this.revocations.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @Override
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
            grantWallet(userId);
            issueRefresh(userId, response);
            return issueAccess(userId);
        });
    }

    @Override
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

    @Override
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
                    // used_at is NULL for every never-rotated (legal) token; a direct
                    // toInstant() on it throws an NPE and turns rotation into a 500.
                    offsetToInstant(rs.getObject("used_at", OffsetDateTime.class)), rs.getString("family_status")),
                    tokenHash);
            if (found.isEmpty()) {
                throw new ApiException(ErrorCode.UNAUTHENTICATED, "refresh token invalid");
            }
            Tok token = found.getFirst();
            boolean reuse = token.usedAt() != null || !"ACTIVE".equals(token.familyStatus());
            if (reuse || token.expiresAt().isBefore(clock.now())) {
                // Reuse of an already-rotated token kills every family and
                // every access token for the user. Rotation stays inside the
                // original family, so the thief's successor token dies with it.
                // The revocations must SURVIVE the error: they run in their own
                // committed transaction because the ApiException thrown below
                // rolls the surrounding transaction back.
                revocations.executeWithoutResult(revocationStatus -> {
                    jdbc.update("UPDATE refresh_families SET status = 'REUSED' WHERE user_id = ?",
                            token.userId());
                    jdbc.update("UPDATE access_tokens SET expires_at = now() WHERE user_id = ?",
                            token.userId());
                    jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?",
                            token.userId());
                });
                clearCookie(response);
                throw new ApiException(ErrorCode.TOKEN_REUSE_DETECTED,
                        "refresh token reuse detected; sessions revoked");
            }
            issueRefresh(token.userId(), token.familyId(), token.id(), response);
            return issueAccess(token.userId());
        });
    }

    private static Instant offsetToInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    @Override
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
    @Override
    public boolean verifyPassword(UUID userId, String password) {
        if (password == null || password.isBlank()) {
            return false;
        }
        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM users WHERE id = ? AND status = 'ACTIVE'",
                String.class, userId);
        return !hashes.isEmpty() && passwordEncoder.matches(password, hashes.getFirst());
    }

    @Override
    public UserInfo me(UUID userId) {
        return loadUser(userId);
    }

    @Override
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

    @Override
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
        UserInfo user = loadUser(userId);
        return new TokenResponse(raw, ttl.toSeconds(), user);
    }

    private UserInfo loadUser(UUID userId) {
        return jdbc.query("""
                SELECT u.id, u.email, u.role, u.display_name,
                       COALESCE(w.available_amount_minor, 0) AS available_amount_minor,
                       COALESCE(w.currency, 'CNY') AS currency
                FROM users u
                LEFT JOIN user_wallets w ON w.user_id = u.id
                WHERE u.id = ?
                """, (rs, i) -> new UserInfo(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("role"), rs.getString("display_name"),
                rs.getObject("available_amount_minor", Long.class), rs.getString("currency")), userId)
                .stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private void grantWallet(UUID userId) {
        long grant = properties.wallet() == null ? 1_000_000L : properties.wallet().signupGrantMinor();
        jdbc.update("""
                INSERT INTO user_wallets (user_id, currency, available_amount_minor)
                VALUES (?, 'CNY', ?)
                ON CONFLICT (user_id) DO NOTHING
                """, userId, grant);
    }

    private void issueRefresh(UUID userId, HttpServletResponse response) {
        issueRefresh(userId, null, null, response);
    }

    /**
     * Login/register pass a null family and mint a new one. Rotation passes
     * the existing family and the token being replaced so the successor stays
     * on the same chain and {@code replaced_by} is populated.
     */
    private void issueRefresh(UUID userId, UUID familyId, UUID previousTokenId, HttpServletResponse response) {
        String raw = CanonicalJson.newOpaqueToken();
        Duration ttl = properties.security().refreshTokenTtl();
        UUID resolvedFamilyId = familyId;
        if (resolvedFamilyId == null) {
            resolvedFamilyId = jdbc.queryForObject(
                    "INSERT INTO refresh_families (user_id) VALUES (?) RETURNING id", UUID.class, userId);
        }
        UUID newTokenId = jdbc.queryForObject("""
                INSERT INTO refresh_tokens (family_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                RETURNING id
                """, UUID.class, resolvedFamilyId, CanonicalJson.sha256Hex(raw),
                java.sql.Timestamp.from(Instant.now().plus(ttl)));
        if (previousTokenId != null) {
            jdbc.update("UPDATE refresh_tokens SET used_at = now(), replaced_by = ? WHERE id = ?",
                    newTokenId, previousTokenId);
        }
        Cookie cookie = new Cookie("ep_refresh", raw);
        cookie.setHttpOnly(true);
        // Secure must be on behind TLS: the prod profile defaults it to true
        // and refuses to start without it (ProdSecurityAssertions). Demo/dev
        // runs on plain-http localhost keep it off via configuration.
        cookie.setSecure(properties.security().refreshCookieSecure() != null
                && properties.security().refreshCookieSecure());
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