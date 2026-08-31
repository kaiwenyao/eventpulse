package dev.kaiwen.eventpulse.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Signed keyset cursor for stable search pagination. The cursor carries the
 * filter hash, the stable sort tuple, the server-generated queryAsOf and an
 * expiry, all HMAC-signed. Clients never specify queryAsOf. The contract
 * guarantees a stable traversal boundary against new inserts; per-page stock
 * is a current hint, re-validated at checkout.
 */
public record SearchCursor(int version, String filterHash, String sort, List<Object> last,
                           Instant queryAsOf, Instant expiresAt) {

    public static final int CURRENT_VERSION = 1;
}
