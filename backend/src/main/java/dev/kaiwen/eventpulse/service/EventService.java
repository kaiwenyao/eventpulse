package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import dev.kaiwen.eventpulse.domain.EventStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.dto.EventDtos.EventRequest;
import dev.kaiwen.eventpulse.dto.EventDtos.EventVo;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.EventRepository;

@Service
public class EventService {

    private final EventRepository events;

    public EventService(EventRepository events) {
        this.events = events;
    }

    public List<EventVo> list(String city, String category, String q) {
        return search(city, category, q, null, null, null, null, null, "startsAt", false).getRecords();
    }

    public dev.kaiwen.eventpulse.common.PageResult<EventVo> search(
            String city, String category, String q, Instant dateFrom, Instant dateTo,
            Integer minPrice, Integer maxPrice, Boolean hasRemaining, String sort, boolean desc) {
        return search(city, category, q, dateFrom, dateTo, minPrice, maxPrice, hasRemaining, sort, desc, null, null);
    }

    public dev.kaiwen.eventpulse.common.PageResult<EventVo> search(
            String city, String category, String q, Instant dateFrom, Instant dateTo,
            Integer minPrice, Integer maxPrice, Boolean hasRemaining, String sort, boolean desc,
            Integer page, Integer size) {
        String cityQuery = city == null ? "" : city.trim().toLowerCase(Locale.ROOT);
        List<EventVo> all = events.findByStatusInOrderByStartsAtAsc(EventStatus.PUBLIC_LIST).stream()
                .filter(event -> cityQuery.isBlank()
                        || event.getCity().toLowerCase(Locale.ROOT).contains(cityQuery))
                .filter(event -> category == null || category.isBlank()
                        || event.getCategory().equalsIgnoreCase(category))
                .filter(event -> q == null || q.isBlank()
                        || event.getTitle().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))
                        || (event.getSummary() != null && event.getSummary().toLowerCase(Locale.ROOT)
                                .contains(q.toLowerCase(Locale.ROOT))))
                .filter(event -> dateFrom == null || !event.getStartsAt().isBefore(dateFrom))
                .filter(event -> dateTo == null || !event.getStartsAt().isAfter(dateTo))
                .filter(event -> minPrice == null || event.getPriceCents() >= minPrice)
                .filter(event -> maxPrice == null || event.getPriceCents() <= maxPrice)
                .filter(event -> hasRemaining == null || !hasRemaining || event.remaining() > 0)
                .sorted((a, b) -> compareEvents(a, b, sort, desc))
                .map(this::toVo)
                .toList();
        if (page == null || size == null || size <= 0) {
            return new dev.kaiwen.eventpulse.common.PageResult<>(all.size(), all);
        }
        int from = Math.max(0, page) * size;
        if (from >= all.size()) {
            return new dev.kaiwen.eventpulse.common.PageResult<>(all.size(), List.of());
        }
        return new dev.kaiwen.eventpulse.common.PageResult<>(all.size(), all.subList(from, Math.min(all.size(), from + size)));
    }

    public EventVo get(Long id) {
        return toVo(require(id));
    }

    public List<EventVo> mine() {
        requireOrganiser();
        return events.findByOrganiserIdOrderByStartsAtDesc(BaseContext.getUserId()).stream()
                .map(this::toVo)
                .toList();
    }

    @Transactional
    public EventVo create(EventRequest request) {
        requireOrganiser();
        Event event = new Event();
        apply(event, request);
        event.setSold(0);
        event.setOrganiserId(BaseContext.getUserId());
        event.setStatus(EventStatus.PUBLISHED);
        event.setCreatedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        if (event.getEndsAt() == null) {
            event.setEndsAt(event.getStartsAt().plus(3, ChronoUnit.HOURS));
        }
        events.save(event);
        return toVo(event);
    }

    @Transactional
    public EventVo update(Long id, EventRequest request) {
        Event event = requireOwn(id);
        if (request.capacity() < event.getSold()) {
            throw new BusinessException("Capacity cannot be below tickets already sold");
        }
        apply(event, request);
        return toVo(event);
    }

    Event require(Long id) {
        return events.findById(id).orElseThrow(() -> BusinessException.notFound("Event not found"));
    }

    private Event requireOwn(Long id) {
        requireOrganiser();
        Event event = require(id);
        if (!event.getOrganiserId().equals(BaseContext.getUserId())) {
            throw BusinessException.forbidden("You can only manage your own events");
        }
        return event;
    }

    public static void requireOrganiser() {
        if (!"ORGANISER".equals(BaseContext.getRole())) {
            throw BusinessException.forbidden("Only organisers can manage events");
        }
    }

    private static void apply(Event event, EventRequest request) {
        event.setTitle(request.title());
        event.setDescription(request.description());
        event.setCategory(request.category());
        event.setCity(request.city());
        event.setStartsAt(request.startsAt());
        event.setEndsAt(request.startsAt().plus(3, ChronoUnit.HOURS));
        event.setPriceCents(request.priceCents());
        event.setCapacity(request.capacity());
        event.setMaxQuantityPerBooking(Math.max(event.getMaxQuantityPerBooking(), 1));
        event.setUpdatedAt(Instant.now());
    }

    public EventVo toVo(Event event) {
        return toVo(event, null);
    }

    public EventVo toVo(Event event, Boolean favourite) {
        Instant now = Instant.now();
        String reason = unbookableReason(event, now);
        return new EventVo(
                event.getId(),
                event.getTitle(),
                event.getSummary(),
                event.getDescription(),
                event.getCategory(),
                event.getCity(),
                event.getVenueName(),
                event.getAddress(),
                event.getLatitude(),
                event.getLongitude(),
                event.getStartsAt(),
                event.getEndsAt(),
                event.getCoverUrl(),
                event.getSalesStartAt(),
                event.getSalesEndAt(),
                event.getMaxQuantityPerBooking() <= 0 ? 10 : event.getMaxQuantityPerBooking(),
                event.getContactInfo(),
                event.getAttendanceNotes(),
                event.getPriceCents(),
                event.getCapacity(),
                event.getSold(),
                event.remaining(),
                event.getOrganiserId(),
                event.getStatus(),
                event.getCancellationReason(),
                event.getCancelledAt(),
                event.getArchivedAt(),
                event.getUpdatedAt(),
                event.getCreatedAt(),
                event.getVersion(),
                favourite,
                reason == null,
                reason);
    }

    /**
     * 不可预订原因的机器键（null = 可预订）。购物车失效提示、订单不可取消原因
     * 都引用这组键，前端再做 i18n；{@link #unbookableReason} 负责英文消息。
     */
    static String unbookableKey(Event event, Instant now) {
        if (!EventStatus.PUBLISHED.equals(event.getStatus())) {
            return EventStatus.CANCELLED.equals(event.getStatus()) ? "EVENT_CANCELLED" : "EVENT_NOT_OPEN";
        }
        if (event.getSalesStartAt() != null && now.isBefore(event.getSalesStartAt())) {
            return "SALES_NOT_STARTED";
        }
        Instant salesEnd = event.getSalesEndAt() != null ? event.getSalesEndAt() : event.getStartsAt();
        if (!now.isBefore(salesEnd)) {
            return "SALES_ENDED";
        }
        if (!now.isBefore(event.getStartsAt())) {
            return "EVENT_STARTED";
        }
        if (event.remaining() <= 0) {
            return "SOLD_OUT";
        }
        return null;
    }

    static String unbookableReason(Event event, Instant now) {
        String key = unbookableKey(event, now);
        if (key == null) {
            return null;
        }
        return switch (key) {
            case "EVENT_CANCELLED" -> "Event cancelled, booking closed";
            case "EVENT_NOT_OPEN" -> "Event is not open for booking";
            case "SALES_NOT_STARTED" -> "Sales have not started";
            case "SALES_ENDED" -> "Sales have ended";
            case "EVENT_STARTED" -> "Event has already started";
            case "SOLD_OUT" -> "Sold out";
            default -> "Event is not open for booking";
        };
    }

    private static int compareEvents(Event a, Event b, String sort, boolean desc) {
        int cmp;
        if ("price".equals(sort)) {
            cmp = Integer.compare(a.getPriceCents(), b.getPriceCents());
        }
        else if ("sold".equals(sort) || "popularity".equals(sort)) {
            cmp = Integer.compare(a.getSold(), b.getSold());
        }
        else if ("updatedAt".equals(sort)) {
            Instant au = a.getUpdatedAt() == null ? Instant.EPOCH : a.getUpdatedAt();
            Instant bu = b.getUpdatedAt() == null ? Instant.EPOCH : b.getUpdatedAt();
            cmp = au.compareTo(bu);
        }
        else {
            cmp = a.getStartsAt().compareTo(b.getStartsAt());
        }
        return desc ? -cmp : cmp;
    }
}
