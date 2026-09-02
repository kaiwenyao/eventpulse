package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.PageResult;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.dto.EventDtos.ArchiveEventRequest;
import dev.kaiwen.eventpulse.dto.EventDtos.CancelEventRequest;
import dev.kaiwen.eventpulse.dto.EventDtos.EventVo;
import dev.kaiwen.eventpulse.dto.EventDtos.OrganiserEventRequest;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.EventAuditLog;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.outbox.KafkaTopics;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.EventAuditLogRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.TicketRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;

@Service
public class OrganiserEventService {

    private final EventRepository events;
    private final BookingRepository bookings;
    private final TicketRepository tickets;
    private final EventAuditLogRepository audits;
    private final EventService eventService;
    private final UserRepository users;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;

    public OrganiserEventService(
            EventRepository events,
            BookingRepository bookings,
            TicketRepository tickets,
            EventAuditLogRepository audits,
            EventService eventService,
            UserRepository users,
            OutboxWriter outbox,
            ObjectMapper objectMapper) {
        this.events = events;
        this.bookings = bookings;
        this.tickets = tickets;
        this.audits = audits;
        this.eventService = eventService;
        this.users = users;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    public PageResult<EventVo> list(String q, String status, String category, Instant from, Instant to, String sort) {
        EventService.requireOrganiser();
        Long organiserId = BaseContext.getUserId();
        List<EventVo> items = events.findByOrganiserIdOrderByStartsAtDesc(organiserId).stream()
                .filter(event -> status == null || status.isBlank() || status.equals(event.getStatus()))
                .filter(event -> category == null || category.isBlank() || category.equalsIgnoreCase(event.getCategory()))
                .filter(event -> q == null || q.isBlank()
                        || event.getTitle().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
                .filter(event -> from == null || !event.getStartsAt().isBefore(from))
                .filter(event -> to == null || !event.getStartsAt().isAfter(to))
                .sorted(organiserSort(sort))
                .map(eventService::toVo)
                .toList();
        return new PageResult<>(items.size(), items);
    }

    public EventVo get(Long id) {
        return eventService.toVo(requireOwn(id));
    }

    @Transactional
    public EventVo create(OrganiserEventRequest request) {
        EventService.requireOrganiser();
        validate(request);
        Event event = new Event();
        apply(event, request);
        event.setSold(0);
        event.setOrganiserId(BaseContext.getUserId());
        event.setCreatedAt(Instant.now());
        boolean publish = Boolean.TRUE.equals(request.publish());
        event.setStatus(publish ? EventStatus.PUBLISHED : EventStatus.DRAFT);
        events.save(event);
        audit(event.getId(), publish ? "PUBLISH" : "CREATE", null, snapshot(event));
        return eventService.toVo(event);
    }

    @Transactional
    public EventVo update(Long id, OrganiserEventRequest request) {
        Event event = requireOwn(id);
        if (request.version() != null && request.version() != event.getVersion()) {
            throw BusinessException.conflict("活动已被其他人修改，请刷新后重试");
        }
        validate(request);
        String before = snapshot(event);
        applyByStatus(event, request);
        events.save(event);
        audit(event.getId(), "UPDATE", before, snapshot(event));
        return eventService.toVo(event);
    }

    @Transactional
    public EventVo publish(Long id) {
        Event event = requireOwn(id);
        if (!EventStatus.canTransition(event.getStatus(), EventStatus.PUBLISHED)) {
            throw BusinessException.conflict("当前状态不能发布");
        }
        String before = snapshot(event);
        event.setStatus(EventStatus.PUBLISHED);
        event.setUpdatedAt(Instant.now());
        events.save(event);
        audit(event.getId(), "PUBLISH", before, snapshot(event));
        return eventService.toVo(event);
    }

    @Transactional
    public EventVo cancel(Long id, CancelEventRequest request) {
        Event event = requireOwn(id);
        if (!EventStatus.canTransition(event.getStatus(), EventStatus.CANCELLED)) {
            throw BusinessException.conflict("当前状态不能取消");
        }
        String before = snapshot(event);
        event.setStatus(EventStatus.CANCELLED);
        event.setCancellationReason(request.reason());
        event.setCancelledAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        events.save(event);
        tickets.findByEventIdOrderByIdAsc(id).forEach(ticket -> {
            if ("VALID".equals(ticket.getStatus())) {
                ticket.setStatus("CANCELLED");
            }
        });
        for (Booking booking : bookings.findByEventIdOrderByCreatedAtDesc(id)) {
            // The event row is already updated above; claim the booking before touching its wallet.
            if (bookings.cancelConfirmed(booking.getId()) == 1) {
                users.creditWallet(booking.getUserId(), booking.getPaidCents());
                outbox.write(KafkaTopics.BOOKING_EVENTS, "EVENT_CANCELLED",
                        "EVENT_CANCELLED:" + id + ":" + booking.getUserId(),
                        Map.of(
                                "type", "EVENT_CANCELLED",
                                "userId", booking.getUserId(),
                                "eventId", id,
                                "bookingId", booking.getId(),
                                "title", "活动已取消",
                                "message", event.getTitle() + " 已取消：" + request.reason()));
            }
        }
        audit(event.getId(), "CANCEL", before, snapshot(event));
        return eventService.toVo(event);
    }

    @Transactional
    public EventVo archive(Long id, ArchiveEventRequest request) {
        Event event = requireOwn(id);
        if (!EventStatus.canTransition(event.getStatus(), EventStatus.ARCHIVED)) {
            throw BusinessException.conflict("只有已结束或已取消的活动可以归档");
        }
        String before = snapshot(event);
        event.setStatus(EventStatus.ARCHIVED);
        event.setArchiveNote(request == null ? null : request.note());
        event.setArchivedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        events.save(event);
        audit(event.getId(), "ARCHIVE", before, snapshot(event));
        return eventService.toVo(event);
    }

    @Transactional
    public EventVo duplicate(Long id) {
        Event source = requireOwn(id);
        Event copy = new Event();
        copy.setTitle(source.getTitle() + "（副本）");
        copy.setSummary(source.getSummary());
        copy.setDescription(source.getDescription());
        copy.setCategory(source.getCategory());
        copy.setCity(source.getCity());
        copy.setVenueName(source.getVenueName());
        copy.setAddress(source.getAddress());
        copy.setLatitude(source.getLatitude());
        copy.setLongitude(source.getLongitude());
        copy.setStartsAt(source.getStartsAt().plus(7, ChronoUnit.DAYS));
        copy.setEndsAt(source.getEndsAt().plus(7, ChronoUnit.DAYS));
        copy.setCoverUrl(source.getCoverUrl());
        copy.setCoverAssetId(source.getCoverAssetId());
        copy.setSalesStartAt(source.getSalesStartAt());
        copy.setSalesEndAt(source.getSalesEndAt());
        copy.setMaxQuantityPerBooking(source.getMaxQuantityPerBooking());
        copy.setContactInfo(source.getContactInfo());
        copy.setAttendanceNotes(source.getAttendanceNotes());
        copy.setPriceCents(source.getPriceCents());
        copy.setCapacity(source.getCapacity());
        copy.setSold(0);
        copy.setOrganiserId(source.getOrganiserId());
        copy.setStatus(EventStatus.DRAFT);
        copy.setCreatedAt(Instant.now());
        copy.setUpdatedAt(Instant.now());
        events.save(copy);
        audit(copy.getId(), "DUPLICATE", null, snapshot(copy));
        return eventService.toVo(copy);
    }

    @Transactional
    public void delete(Long id) {
        Event event = requireOwn(id);
        if (!EventStatus.DRAFT.equals(event.getStatus())) {
            throw BusinessException.conflict("只有草稿可以删除");
        }
        boolean hasOrders = !bookings.findByEventIdOrderByCreatedAtDesc(id).isEmpty();
        if (hasOrders) {
            throw BusinessException.conflict("已有订单的活动不能物理删除");
        }
        audit(id, "DELETE", snapshot(event), null);
        events.delete(event);
    }

    public Event requireOwn(Long id) {
        EventService.requireOrganiser();
        return events.findByIdAndOrganiserId(id, BaseContext.getUserId())
                .orElseThrow(() -> {
                    if (events.findById(id).isPresent()) {
                        return BusinessException.forbidden("只能操作自己的活动");
                    }
                    return BusinessException.notFound("活动不存在");
                });
    }

    public List<String> allowedActions(Event event) {
        List<String> actions = new ArrayList<>();
        actions.add("view");
        actions.add("duplicate");
        switch (event.getStatus()) {
            case EventStatus.DRAFT -> {
                actions.add("edit");
                actions.add("preview");
                actions.add("publish");
                actions.add("delete");
            }
            case EventStatus.PUBLISHED -> {
                actions.add("edit");
                actions.add("cancel");
                actions.add("attendees");
            }
            case EventStatus.ONGOING -> {
                actions.add("edit");
                actions.add("attendees");
            }
            case EventStatus.FINISHED, EventStatus.CANCELLED -> {
                actions.add("archive");
                actions.add("attendees");
            }
            default -> {
            }
        }
        return actions;
    }

    private void applyByStatus(Event event, OrganiserEventRequest request) {
        String status = event.getStatus();
        if (EventStatus.FINISHED.equals(status) || EventStatus.CANCELLED.equals(status)
                || EventStatus.ARCHIVED.equals(status)) {
            if (EventStatus.FINISHED.equals(status)) {
                event.setArchiveNote(request.attendanceNotes());
                event.setUpdatedAt(Instant.now());
                return;
            }
            throw BusinessException.conflict("当前状态不可编辑");
        }
        if (EventStatus.ONGOING.equals(status)) {
            event.setContactInfo(request.contactInfo());
            event.setAttendanceNotes(request.attendanceNotes());
            event.setUpdatedAt(Instant.now());
            return;
        }
        if (request.capacity() < event.getSold()) {
            throw new BusinessException("容量不能小于已售出票数");
        }
        apply(event, request);
    }

    private void apply(Event event, OrganiserEventRequest request) {
        event.setTitle(request.title());
        event.setSummary(request.summary());
        event.setDescription(request.description());
        event.setCategory(request.category());
        event.setCoverUrl(request.coverUrl());
        event.setCoverAssetId(request.coverAssetId());
        event.setStartsAt(request.startsAt());
        event.setEndsAt(request.endsAt() != null ? request.endsAt() : request.startsAt().plus(3, ChronoUnit.HOURS));
        event.setCity(request.city());
        event.setVenueName(request.venueName());
        event.setAddress(request.address());
        event.setLatitude(request.latitude());
        event.setLongitude(request.longitude());
        event.setPriceCents(request.priceCents());
        event.setCapacity(request.capacity());
        event.setSalesStartAt(request.salesStartAt());
        event.setSalesEndAt(request.salesEndAt());
        event.setMaxQuantityPerBooking(request.maxQuantityPerBooking() == null ? 10 : request.maxQuantityPerBooking());
        event.setContactInfo(request.contactInfo());
        event.setAttendanceNotes(request.attendanceNotes());
        event.setUpdatedAt(Instant.now());
    }

    private void validate(OrganiserEventRequest request) {
        Instant ends = request.endsAt() != null ? request.endsAt() : request.startsAt().plus(3, ChronoUnit.HOURS);
        if (!request.startsAt().isBefore(ends)) {
            throw new BusinessException("开始时间必须早于结束时间");
        }
        if (request.salesEndAt() != null && request.salesEndAt().isAfter(request.startsAt())) {
            throw new BusinessException("售票截止时间不能晚于活动开始时间");
        }
        if (request.capacity() <= 0) {
            throw new BusinessException("容量必须大于零");
        }
        if (request.priceCents() < 0) {
            throw new BusinessException("票价不能为负数");
        }
    }

    private void audit(Long eventId, String action, String before, String after) {
        EventAuditLog log = new EventAuditLog();
        log.setEventId(eventId);
        log.setOperatorId(BaseContext.getUserId());
        log.setAction(action);
        log.setBeforeData(before);
        log.setAfterData(after);
        log.setCreatedAt(Instant.now());
        audits.save(log);
    }

    private String snapshot(Event event) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "title", event.getTitle(),
                    "status", event.getStatus(),
                    "startsAt", String.valueOf(event.getStartsAt()),
                    "city", event.getCity(),
                    "capacity", event.getCapacity(),
                    "sold", event.getSold()));
        }
        catch (JsonProcessingException e) {
            return event.getStatus();
        }
    }

    private static Comparator<Event> organiserSort(String sort) {
        boolean desc = sort == null || sort.endsWith(",desc") || !sort.contains(",");
        String field = sort == null ? "updatedAt" : sort.split(",")[0];
        Comparator<Event> cmp = switch (field) {
            case "startsAt" -> Comparator.comparing(Event::getStartsAt);
            case "sold" -> Comparator.comparingInt(Event::getSold);
            default -> Comparator.comparing(event -> event.getUpdatedAt() == null ? Instant.EPOCH : event.getUpdatedAt());
        };
        return desc ? cmp.reversed() : cmp;
    }
}
