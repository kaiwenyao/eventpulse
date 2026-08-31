package dev.kaiwen.eventpulse.service.impl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.common.CursorCodec;
import dev.kaiwen.eventpulse.common.DbClock;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.EventDetail;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.EventListItem;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.SearchResult;
import dev.kaiwen.eventpulse.dto.CatalogueDtos.TierView;
import dev.kaiwen.eventpulse.dto.SearchCursor;
import dev.kaiwen.eventpulse.exception.ApiException;
import dev.kaiwen.eventpulse.exception.ErrorCode;
import dev.kaiwen.eventpulse.outbox.OutboxJson;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.service.CatalogueService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

@Service
public class CatalogueServiceImpl implements CatalogueService {

    private static final double MAX_RADIUS_KM = 50.0;

    private final JdbcTemplate jdbc;
    /** Search/nearby/detail reads go to the read-only search pool (§3.1): a
     *  heavy vector/geo query can never hold a transactional-pool connection. */
    private final JdbcTemplate searchJdbc;
    private final TransactionTemplate tx;
    private final OutboxWriter outbox;
    private final CursorCodec cursorCodec;
    private final ObjectMapper mapper = OutboxJson.mapper();
    private final DbClock clock;

    public CatalogueServiceImpl(@org.springframework.beans.factory.annotation.Qualifier(
            "txJdbcTemplate") JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Qualifier(
                    "searchJdbcTemplate") JdbcTemplate searchJdbc,
            TransactionTemplate tx, OutboxWriter outbox, CursorCodec cursorCodec, DbClock clock) {
        this.jdbc = jdbc;
        this.searchJdbc = searchJdbc;
        this.tx = tx;
        this.outbox = outbox;
        this.cursorCodec = cursorCodec;
        this.clock = clock;
    }

    private static final RowMapper<EventListItem> LIST_MAPPER = (rs, i) -> new EventListItem(
            rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("category"),
            rs.getString("status"), rs.getObject("starts_at", OffsetDateTime.class).toInstant(), rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
            rs.getString("venue_name"), rs.getString("city"),
            rs.getObject("lat", Double.class), rs.getObject("lng", Double.class),
            rs.getObject("min_price", Long.class), rs.getString("currency"),
            rs.getObject("available", Long.class), rs.getString("image_url"));

    @Override
    public SearchResult search(String q, String category, String city, Long priceMin, Long priceMax,
            Instant from, Instant to, boolean availableOnly, String sort, String cursor, Integer limit) {
        Map<String, Object> filterMap = new HashMap<>();
        filterMap.put("q", q);
        filterMap.put("category", category);
        filterMap.put("city", city);
        filterMap.put("priceMin", priceMin);
        filterMap.put("priceMax", priceMax);
        filterMap.put("from", from == null ? null : from.toString());
        filterMap.put("to", to == null ? null : to.toString());
        filterMap.put("availableOnly", availableOnly);
        String filterHash = CanonicalJson.sha256Hex(CanonicalJson.canonicalize(filterMap, mapper));

        Instant queryAsOf;
        SearchCursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            decoded = cursorCodec.decode(cursor);
            if (!filterHash.equals(decoded.filterHash())) {
                throw new ApiException(ErrorCode.CURSOR_INVALID, "filters changed, restart from page one");
            }
            queryAsOf = decoded.queryAsOf();
        }
        else {
            queryAsOf = clock.now();
        }
        String sortKey = decoded != null && decoded.sort() != null ? decoded.sort() : sortOrDefault(sort);
        int pageSize = limit == null || limit < 1 || limit > 50 ? 20 : limit;

        StringBuilder where = new StringBuilder(
                " e.status = 'PUBLISHED' AND e.starts_at >= ? ");
        if (to != null) {
            where.append("AND e.starts_at <= ? ");
        }
        if (category != null && !category.isBlank()) {
            where.append("AND e.category = ? ");
        }
        if (city != null && !city.isBlank()) {
            where.append("AND v.city = ? ");
        }
        if (priceMin != null) {
            where.append("AND COALESCE(tp.min_price, 0) >= ? ");
        }
        if (priceMax != null) {
            where.append("AND COALESCE(tp.min_price, 0) <= ? ");
        }
        if (availableOnly) {
            where.append("AND COALESCE(av.total_available, 0) > 0 ");
        }
        if (q != null && !q.isBlank()) {
            where.append("AND (e.title ILIKE ? OR e.description ILIKE ?) ");
        }
        String orderClause = " " + switch (sortKey) {
            case "price" -> "COALESCE(tp.min_price, 0) ASC, e.id ASC";
            case "newest" -> "e.created_at DESC, e.id DESC";
            default -> "e.starts_at ASC, e.id ASC";
        } + " ";
        String tupleValue = switch (sortKey) {
            case "price" -> "COALESCE(tp.min_price, 0)";
            case "newest" -> "e.created_at";
            default -> "e.starts_at";
        };
        Object[] params = buildParams(from, queryAsOf, to, category, city, priceMin, priceMax, q);
        String keysetCondition = "";
        Object[] allParams = params;
        if (decoded != null && decoded.last() != null && decoded.last().size() == 2) {
            Object lastValue = decodeSortValue(sortKey, decoded.last().get(0));
            if (lastValue instanceof Instant instant) {
                lastValue = java.sql.Timestamp.from(instant);
            }
            UUID lastId = UUID.fromString(String.valueOf(decoded.last().get(1)));
            String cmp = "newest".equals(sortKey) ? "<" : ">";
            keysetCondition = "AND (" + tupleValue + ", e.id) " + cmp + " (?, ?) ";
            allParams = appendParams(params, lastValue, lastId);
        }

        String sql = """
                SELECT e.id, e.title, e.category, e.status, e.starts_at, e.ends_at,
                       v.name AS venue_name, v.city, v.location::geography IS NOT NULL AS has_geo,
                       ST_Y(v.location::geometry) AS lat, ST_X(v.location::geometry) AS lng,
                       tp.min_price, tp.currency, av.total_available AS available,
                       e.cover_image_url AS image_url
                FROM events e
                LEFT JOIN venues v ON v.id = e.venue_id
                LEFT JOIN (SELECT event_id, MIN(unit_price_minor) AS min_price,
                                  MIN(currency) AS currency FROM ticket_tiers GROUP BY event_id) tp
                       ON tp.event_id = e.id
                LEFT JOIN (SELECT t.event_id, SUM(i.available) AS total_available
                           FROM ticket_tiers t JOIN inventory i ON i.tier_id = t.id
                           WHERE t.status = 'ACTIVE' GROUP BY t.event_id) av ON av.event_id = e.id
                WHERE """ + where + keysetCondition + """
                ORDER BY """ + orderClause + """
                LIMIT ?
                """;
        List<EventListItem> items = searchJdbc.query(sql, LIST_MAPPER,
                appendParams(allParams, pageSize + 1));

        String nextCursor = null;
        List<EventListItem> page = items;
        if (items.size() > pageSize) {
            page = items.subList(0, pageSize);
            EventListItem last = page.getLast();
            Object lastSortValue = switch (sortKey) {
                case "price" -> last.minPriceMinor() == null ? 0L : last.minPriceMinor();
                case "newest" -> null; // handled below via detail query
                default -> last.startsAt();
            };
            if ("newest".equals(sortKey)) {
                // created_at is not projected; fetch it for the tuple.
                lastSortValue = searchJdbc.queryForObject("SELECT created_at FROM events WHERE id = ?",
                        java.time.OffsetDateTime.class, last.id()).toInstant();
            }
            SearchCursor next = new SearchCursor(SearchCursor.CURRENT_VERSION, filterHash, sortKey,
                    List.of(encodeSortValue(sortKey, lastSortValue), last.id().toString()), queryAsOf,
                    cursorCodec.newExpiry());
            nextCursor = cursorCodec.encode(next);
        }
        return new SearchResult(page, nextCursor, queryAsOf);
    }

    @Override
    public SearchResult nearby(double lat, double lng, double radiusKm, Integer limit) {
        double capped = Math.min(Math.max(radiusKm, 0.1), MAX_RADIUS_KM);
        int pageSize = limit == null || limit < 1 || limit > 50 ? 20 : limit;
        String sql = """
                SELECT e.id, e.title, e.category, e.status, e.starts_at, e.ends_at,
                       v.name AS venue_name, v.city,
                       ST_Y(v.location::geometry) AS lat, ST_X(v.location::geometry) AS lng,
                       tp.min_price, tp.currency, av.total_available AS available,
                       e.cover_image_url AS image_url
                FROM events e
                JOIN venues v ON v.id = e.venue_id
                LEFT JOIN (SELECT event_id, MIN(unit_price_minor) AS min_price,
                                  MIN(currency) AS currency FROM ticket_tiers GROUP BY event_id) tp
                       ON tp.event_id = e.id
                LEFT JOIN (SELECT t.event_id, SUM(i.available) AS total_available
                           FROM ticket_tiers t JOIN inventory i ON i.tier_id = t.id
                           WHERE t.status = 'ACTIVE' GROUP BY t.event_id) av ON av.event_id = e.id
                WHERE e.status = 'PUBLISHED'
                  AND ST_DWithin(v.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                ORDER BY e.starts_at ASC, e.id ASC
                LIMIT ?
                """;
        List<EventListItem> items = searchJdbc.query(sql, LIST_MAPPER, lng, lat, capped * 1000,
                pageSize + 1);
        List<EventListItem> page = items.size() > pageSize ? items.subList(0, pageSize) : items;
        // Nearby is distance-ordered; no keyset cursor is exposed for it in the MVP.
        return new SearchResult(page, null, clock.now());
    }

    @Override
    public EventDetail detail(UUID eventId) {
        // Event detail is a catalogue (vector/geo-bound) read: the read-only
        // search pool keeps it off the transactional pool (§3.1).
        EventDetail detail = searchJdbc.query("""
                SELECT e.id, e.title, e.description, e.category, e.status, e.starts_at, e.ends_at,
                       e.age_requirement, e.policy_version, e.policy::text AS policy,
                       v.name AS venue_name, v.city, ST_Y(v.location::geometry) AS lat,
                       ST_X(v.location::geometry) AS lng, v.timezone, o.name AS organiser_name,
                       e.updated_at
                FROM events e
                LEFT JOIN venues v ON v.id = e.venue_id
                JOIN organisers o ON o.id = e.organiser_id
                WHERE e.id = ?
                """, (rs, i) -> new EventDetail(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("description"), rs.getString("category"), rs.getString("status"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(), rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                rs.getObject("age_requirement", Integer.class), rs.getInt("policy_version"),
                rs.getString("policy") == null ? Map.of() : mapper.readValue(rs.getString("policy"), Map.class),
                rs.getString("venue_name"), rs.getString("city"), rs.getObject("lat", Double.class),
                rs.getObject("lng", Double.class), rs.getString("timezone"), rs.getString("organiser_name"),
                List.of(), rs.getObject("updated_at", OffsetDateTime.class).toInstant()), eventId).stream().findFirst()
                .orElseThrow(ApiException::notFound);
        if (!"PUBLISHED".equals(detail.status())) {
            // Drafts and cancelled events are hidden from non-owners.
            throw ApiException.notFound();
        }
        List<TierView> tiers = searchJdbc.query("""
                SELECT t.id, t.name, t.unit_price_minor, t.currency, t.sale_start_at, t.sale_end_at,
                       t.per_user_limit, t.status, i.capacity, i.available, i.sold
                FROM ticket_tiers t LEFT JOIN inventory i ON i.tier_id = t.id
                WHERE t.event_id = ? AND t.status <> 'DRAFT'
                ORDER BY t.unit_price_minor ASC
                """, (rs, i) -> new TierView(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("unit_price_minor", Long.class), rs.getString("currency"),
                rs.getObject("sale_start_at", OffsetDateTime.class).toInstant(), rs.getObject("sale_end_at", OffsetDateTime.class).toInstant(),
                rs.getObject("per_user_limit", Integer.class), rs.getString("status"),
                rs.getObject("capacity", Integer.class), rs.getObject("available", Integer.class),
                rs.getObject("sold", Integer.class)), eventId);
        return new EventDetail(detail.id(), detail.title(), detail.description(), detail.category(),
                detail.status(), detail.startsAt(), detail.endsAt(), detail.ageRequirement(),
                detail.policyVersion(), detail.policy(), detail.venueName(), detail.city(), detail.lat(),
                detail.lng(), detail.timezone(), detail.organiserName(), tiers, detail.updatedAt());
    }

    @Override
    public String detailEtag(UUID eventId) {
        String updated = jdbc.queryForObject(
                "SELECT updated_at::text FROM events WHERE id = ? AND status = 'PUBLISHED'", String.class,
                eventId);
        if (updated == null) {
            throw ApiException.notFound();
        }
        return '"' + CanonicalJson.sha256Hex(eventId + updated).substring(0, 24) + '"';
    }

    private String sortOrDefault(String sort) {
        if (sort == null || sort.isBlank()) {
            return "starts_at";
        }
        return switch (sort) {
            case "starts_at", "price", "newest" -> sort;
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "unsupported sort: " + sort);
        };
    }

    private Object encodeSortValue(String sortKey, Object value) {
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        return value;
    }

    private Object decodeSortValue(String sortKey, Object value) {
        if (value instanceof String s && ("starts_at".equals(sortKey) || "newest".equals(sortKey))) {
            return Instant.parse(s);
        }
        return value;
    }

    private Object[] buildParams(Instant from, Instant queryAsOf, Instant to, String category, String city,
            Long priceMin, Long priceMax, String q) {
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(java.sql.Timestamp.from(from != null ? from : queryAsOf));
        if (to != null) {
            params.add(java.sql.Timestamp.from(to));
        }
        if (category != null && !category.isBlank()) {
            params.add(category);
        }
        if (city != null && !city.isBlank()) {
            params.add(city);
        }
        if (priceMin != null) {
            params.add(priceMin);
        }
        if (priceMax != null) {
            params.add(priceMax);
        }
        if (q != null && !q.isBlank()) {
            params.add("%" + q + "%");
            params.add("%" + q + "%");
        }
        return params.toArray();
    }

    private Object[] appendParams(Object[] params, Object... extra) {
        Object[] out = new Object[params.length + extra.length];
        System.arraycopy(params, 0, out, 0, params.length);
        System.arraycopy(extra, 0, out, params.length, extra.length);
        return out;
    }
}