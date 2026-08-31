package dev.kaiwen.eventpulse.common.web;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.security.AuthUser;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Resolves the opaque bearer access token to an AuthUser. Banned users,
 * rotated-away token versions and expired tokens are all simply unauthenticated.
 */
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private record Row(UUID id, String email, String role, String status, int tokenVersion,
                       Instant expiresAt) {
    }

    public record OrganiserRow(UUID organiserId, String name) {
    }

    private final JdbcTemplate jdbc;

    public TokenAuthenticationFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            String hash = CanonicalJson.sha256Hex(token);
            List<Row> rows = jdbc.query("""
                    SELECT u.id, u.email, u.role, u.status, u.token_version, t.expires_at
                    FROM access_tokens t JOIN users u ON u.id = t.user_id
                    WHERE t.token_hash = ?
                    """, (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("email"),
                    rs.getString("role"), rs.getString("status"), rs.getInt("token_version"),
                    rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
                    hash);
            if (!rows.isEmpty()) {
                Row row = rows.getFirst();
                if (row.expiresAt().isAfter(Instant.now()) && !"BANNED".equals(row.status())) {
                    List<OrganiserRow> owned = jdbc.query("""
                            SELECT id, name FROM organisers WHERE owner_user_id = ? AND status = 'ACTIVE'
                            """, (rs, i) -> new OrganiserRow(rs.getObject("id", UUID.class), rs.getString("name")),
                            row.id());
                    AuthUser user = new AuthUser(row.id(), row.email(), row.role(), row.tokenVersion(),
                            owned.stream().map(o -> new AuthUser.OrganiserRef(o.organiserId(), o.name())).toList());
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(user, null,
                            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                    "ROLE_" + row.role())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(request, response);
    }

    private static String role(Row row) {
        return row.role() == null ? "" : row.role();
    }
}
