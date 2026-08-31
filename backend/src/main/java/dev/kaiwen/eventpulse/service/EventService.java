package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

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
        return events.findByStatusOrderByStartsAtAsc("PUBLISHED").stream()
                .filter(event -> city == null || city.isBlank() || event.getCity().equalsIgnoreCase(city))
                .filter(event -> category == null || category.isBlank()
                        || event.getCategory().equalsIgnoreCase(category))
                .filter(event -> q == null || q.isBlank()
                        || event.getTitle().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
                .map(EventService::toVo)
                .toList();
    }

    public EventVo get(Long id) {
        return toVo(require(id));
    }

    public List<EventVo> mine() {
        requireOrganiser();
        return events.findByOrganiserIdOrderByStartsAtDesc(BaseContext.getUserId()).stream()
                .map(EventService::toVo)
                .toList();
    }

    @Transactional
    public EventVo create(EventRequest request) {
        requireOrganiser();
        Event event = new Event();
        apply(event, request);
        event.setSold(0);
        event.setOrganiserId(BaseContext.getUserId());
        event.setStatus("PUBLISHED");
        event.setCreatedAt(Instant.now());
        events.save(event);
        return toVo(event);
    }

    @Transactional
    public EventVo update(Long id, EventRequest request) {
        Event event = requireOwn(id);
        if (request.capacity() < event.getSold()) {
            throw new BusinessException("容量不能小于已售出票数");
        }
        apply(event, request);
        return toVo(event);
    }

    @Transactional
    public void cancel(Long id) {
        Event event = requireOwn(id);
        event.setStatus("CANCELLED");
    }

    Event require(Long id) {
        return events.findById(id).orElseThrow(() -> new BusinessException("活动不存在"));
    }

    private Event requireOwn(Long id) {
        requireOrganiser();
        Event event = require(id);
        if (!event.getOrganiserId().equals(BaseContext.getUserId())) {
            throw new BusinessException("只能操作自己的活动");
        }
        return event;
    }

    private static void requireOrganiser() {
        if (!"ORGANISER".equals(BaseContext.getRole())) {
            throw new BusinessException("只有主办方可以管理活动");
        }
    }

    private static void apply(Event event, EventRequest request) {
        event.setTitle(request.title());
        event.setDescription(request.description());
        event.setCategory(request.category());
        event.setCity(request.city());
        event.setStartsAt(request.startsAt());
        event.setPriceCents(request.priceCents());
        event.setCapacity(request.capacity());
    }

    static EventVo toVo(Event event) {
        return new EventVo(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getCategory(),
                event.getCity(),
                event.getStartsAt(),
                event.getPriceCents(),
                event.getCapacity(),
                event.getSold(),
                event.remaining(),
                event.getOrganiserId(),
                event.getStatus());
    }
}
