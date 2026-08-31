package dev.kaiwen.eventpulse.security;

import java.util.List;

/**
 * Authenticated principal resolved from the opaque access token. Role and
 * ownership always come from the server, never from client input.
 */
public record AuthUser(java.util.UUID id, String email, String role, int tokenVersion,
                       List<OrganiserRef> ownedOrganisers) {

    public record OrganiserRef(java.util.UUID organiserId, String name) {
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isOrganiser() {
        return "ORGANISER".equals(role) || "ADMIN".equals(role);
    }
}
