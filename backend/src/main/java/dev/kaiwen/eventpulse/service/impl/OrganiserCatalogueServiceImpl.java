package dev.kaiwen.eventpulse.service.impl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.dto.CatalogueDtos.CreateEventRequest;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.FunnelRow;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.TierInput;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.UpdateEventRequest;
import dev.kaiwen.eventpulse.common.DbClock;
import dev.kaiwen.eventpulse.exception.ApiException;
import dev.kaiwen.eventpulse.exception.ErrorCode;
import dev.kaiwen.eventpulse.outbox.OutboxJson;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.security.AuthUser;
import dev.kaiwen.eventpulse.service.OrganiserCatalogueService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Organiser-side catalogue management. Ownership is resolved from the
 * authenticated principal; capacity adjustments can never go below
 * reserved + sold + withheld. Event cancellation stops new bookings in one
 * transaction, then walks the affected orders with a stable id cursor.
 */
@Service
public class OrganiserCatalogueServiceImpl implements OrganiserCatalogueService {

    private static final Logger log = LoggerFactory.getLogger(OrganiserCatalogueServiceImpl.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OutboxWriter outbox;
    private final DbClock clock;

    public OrganiserCatalogueServiceImpl(JdbcTemplate jdbc, TransactionTemplate tx, OutboxWriter outbox, DbClock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public UUID createEvent(AuthUser user, CreateEventRequest request) {
        requireOrganiser(user);
        validate(request);
        UUID organiserId = ownedOrganiser(user);
        return tx.execute(status -> {
            UUID venueId = null;
            if (request.venue() != null) {
                venueId = jdbc.queryForObject("""
                        INSERT INTO venues (name, address, city, country, location, timezone)
                        VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                        RETURNING id
                        """, UUID.class, request.venue().name(), request.venue().address(),
                        request.venue().city(), request.venue().country(), request.venue().lng(),
                        request.venue().lat(),
                        request.venue().timezone() == null ? "UTC" : request.venue().timezone());
            }
            UUID eventId = jdbc.queryForObject("""
                    INSERT INTO events (organiser_id, venue_id, title, description, category, starts_at,
                                        ends_at, age_requirement, policy)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    RETURNING id
                    """, UUID.class, organiserId, venueId, request.title(), request.description(),
                    request.category(), java.sql.Timestamp.from(request.startsAt()),
                    java.sql.Timestamp.from(request.endsAt()), request.ageRequirement(),
                    OutboxJson.write(request.policy() == null ? defaultPolicy() : request.policy()));
            for (TierInput tier : request.tiers() == null ? List.<TierInput>of() : request.tiers()) {
                UUID tierId = jdbc.queryForObject("""
                        INSERT INTO ticket_tiers (event_id, name, currency, unit_price_minor, sale_start_at,
                                                  sale_end_at, per_user_limit, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                        RETURNING id
                        """, UUID.class, eventId, tier.name(), tier.currency(), tier.unitPriceMinor(),
                        java.sql.Timestamp.from(tier.saleStartAt()), java.sql.Timestamp.from(tier.saleEndAt()),
                        tier.perUserLimit());
                jdbc.update("""
                        INSERT INTO inventory (tier_id, capacity, available, reserved, sold, withheld)
                        VALUES (?, ?, ?, 0, 0, 0)
                        """, tierId, tier.capacity(), tier.capacity());
            }
            return eventId;
        });
    }

    @Override
    public void publishEvent(AuthUser user, UUID eventId) {
        requireOrganiser(user);
        UUID organiserId = ownedOrganiser(user);
        int rows = tx.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE events SET status = 'PUBLISHED', updated_at = now()
                    WHERE id = ? AND organiser_id = ? AND status = 'DRAFT'
                    """, eventId, organiserId);
            if (updated == 1) {
                jdbc.update("""
                        UPDATE ticket_tiers SET status = 'ACTIVE'
                        WHERE event_id = ? AND status = 'DRAFT'
                          AND sale_end_at > now()
                        """, eventId);
            }
            return updated;
        });
        if (rows != 1) {
            throw new ApiException(ErrorCode.CONFLICT, "event is not in draft state or not owned");
        }
        outbox.append("event", eventId, OutboxWriter.TOPIC_CATALOGUE, "event.published", Map.of(
                "eventId", eventId.toString(), "organiserId", organiserId.toString()));
    }

    @Override
    public void updateEvent(AuthUser user, UUID eventId, UpdateEventRequest request) {
        requireOrganiser(user);
        UUID organiserId = ownedOrganiser(user);
        List<Instant> updated = jdbc.query("""
                UPDATE events SET
                  title = COALESCE(?, title),
                  description = COALESCE(?, description),
                  category = COALESCE(?, category),
                  starts_at = COALESCE(?, starts_at),
                  ends_at = COALESCE(?, ends_at),
                  age_requirement = COALESCE(?, age_requirement),
                  policy = COALESCE(?::jsonb, policy),
                  updated_at = now()
                WHERE id = ? AND organiser_id = ? AND status = 'DRAFT'
                RETURNING updated_at
                """, (rs, i) -> rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                request.title(), request.description(), request.category(),
                request.startsAt() == null ? null : java.sql.Timestamp.from(request.startsAt()),
                request.endsAt() == null ? null : java.sql.Timestamp.from(request.endsAt()),
                request.ageRequirement(),
                request.policy() == null ? null : OutboxJson.write(request.policy()),
                eventId, organiserId);
        if (updated.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "only draft events can be edited");
        }
    }

    /**
     * Stops new bookings immediately, then cancels affected bookings in pages
     * using a stable id cursor so progress survives restarts and re-runs.
     */
    @Override
    public UUID cancelEvent(AuthUser user, UUID eventId, String reason) {
        requireOrganiser(user);
        UUID organiserId = ownedOrganiser(user);
        int stopped = tx.execute(status -> jdbc.update(
                "UPDATE events SET status = 'CANCELLED', updated_at = now() "
                        + "WHERE id = ? AND organiser_id = ? AND status <> 'CANCELLED'",
                eventId, organiserId));
        if (stopped != 1) {
            throw new ApiException(ErrorCode.CONFLICT, "event not found, not owned, or already cancelled");
        }
        outbox.append("event", eventId, OutboxWriter.TOPIC_CATALOGUE, "event.cancelled", Map.of(
                "eventId", eventId.toString(), "organiserId", organiserId.toString(), "reason", reason == null
                        ? "" : reason));
        // Booking cancellations themselves are executed per-booking by the
        // cancellation batch runner; see BookingCancellationBatch.
        return eventId;
    }

    @Override
    public void adjustCapacity(AuthUser user, UUID tierId, int newCapacity, Long expectedVersion,
            String traceId) {
        requireOrganiser(user);
        int rows = tx.execute(status -> {
            record Tier(UUID id, UUID eventId, UUID organiserId) {
            }
            List<Tier> tier = jdbc.query("""
                    SELECT t.id, t.event_id, e.organiser_id
                    FROM ticket_tiers t JOIN events e ON e.id = t.event_id
                    WHERE t.id = ?
                    """, (rs, i) -> new Tier(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                    rs.getObject("organiser_id", UUID.class)), tierId);
            if (tier.isEmpty() || !tier.getFirst().organiserId().equals(ownedOrganiser(user))) {
                throw ApiException.notFound();
            }
            // Only the inventory row is locked here; protocol A/B never take an
            // inventory lock and then wait on booking/quota, so no back-edge exists.
            int updated = jdbc.update("""
                    UPDATE inventory
                    SET capacity = ?, available = ? - (reserved + sold + withheld), version = version + 1
                    WHERE tier_id = ? AND version = COALESCE(?, version)
                      AND capacity >= 0
                      AND ? >= reserved + sold + withheld
                      AND ? - (reserved + sold + withheld) >= 0
                    """, newCapacity, newCapacity, tierId, expectedVersion, newCapacity, newCapacity);
            if (updated == 1) {
                jdbc.update("UPDATE ticket_tiers SET version = version + 1 WHERE id = ?", tierId);
            }
            return updated;
        });
        if (rows != 1) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "capacity change rejected: version conflict or capacity below reserved + sold + withheld");
        }
        outbox.append("event", tierId, OutboxWriter.TOPIC_CATALOGUE, "tier.inventory_changed",
                Map.of("tierId", tierId.toString(), "capacity", newCapacity));
    }

    @Override
    public List<FunnelRow> funnel(AuthUser user) {
        UUID organiserId = ownedOrganiser(user);
        return jdbc.query("""
                SELECT e.id, e.title, e.status, e.starts_at,
                  (SELECT COUNT(*) FROM interactions it WHERE it.event_id = e.id AND it.type = 'VIEW') AS views,
                  (SELECT COUNT(*) FROM saved_events s WHERE s.event_id = e.id) AS saves,
                  (SELECT COUNT(*) FROM bookings b WHERE b.event_id = e.id) AS bookings_created,
                  (SELECT COUNT(*) FROM bookings b WHERE b.event_id = e.id AND b.status = 'CONFIRMED')
                    AS bookings_confirmed,
                  (SELECT COUNT(*) FROM tickets t JOIN bookings b ON b.id = t.booking_id
                     WHERE b.event_id = e.id AND t.status <> 'REVOKED') AS tickets_issued
                FROM events e WHERE e.organiser_id = ? ORDER BY e.starts_at ASC
                """, (rs, i) -> new FunnelRow(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("status"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(), rs.getLong("views"),
                rs.getLong("saves"), rs.getLong("bookings_created"), rs.getLong("bookings_confirmed"),
                rs.getLong("tickets_issued")), organiserId);
    }

    private void validate(CreateEventRequest request) {
        List<String> fields = new ArrayList<>();
        if (request.title() == null || request.title().isBlank()) {
            fields.add("title required");
        }
        if (request.category() == null || request.category().isBlank()) {
            fields.add("category required");
        }
        if (request.startsAt() == null || request.endsAt() == null
                || !request.endsAt().isAfter(request.startsAt())) {
            fields.add("endsAt must be after startsAt");
        }
        if (request.tiers() == null || request.tiers().isEmpty()) {
            fields.add("at least one ticket tier required");
        }
        else {
            for (TierInput tier : request.tiers()) {
                if (tier.unitPriceMinor() == null || tier.unitPriceMinor() < 0) {
                    fields.add("tier price must be >= 0");
                }
                if (tier.perUserLimit() == null || tier.perUserLimit() < 1) {
                    fields.add("tier perUserLimit must be >= 1");
                }
                if (tier.capacity() == null || tier.capacity() < 0) {
                    fields.add("tier capacity must be >= 0");
                }
                if (tier.saleStartAt() == null || tier.saleEndAt() == null
                        || !tier.saleEndAt().isAfter(tier.saleStartAt())) {
                    fields.add("tier sale window invalid");
                }
            }
        }
        if (!fields.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid event", Map.of("fields",
                    String.join("; ", fields)));
        }
    }

    private Map<String, Object> defaultPolicy() {
        return Map.of("cancellable", true, "cancellationDeadlineHoursBeforeStart", 24,
                "resaleAllowed", false, "version", 1);
    }

    void requireOrganiser(AuthUser user) {
        if (user == null || !user.isOrganiser()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "organiser role required");
        }
    }

    UUID ownedOrganiser(AuthUser user) {
        return user.ownedOrganisers().stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "no organiser profile")).organiserId();
    }
}