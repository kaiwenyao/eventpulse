package dev.kaiwen.eventpulse.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.PageResult;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.dto.BookingDtos.NotificationVo;
import dev.kaiwen.eventpulse.dto.EventDtos.EventVo;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.EventDailyMetric;
import dev.kaiwen.eventpulse.entity.EventFavourite;
import dev.kaiwen.eventpulse.entity.Notification;
import dev.kaiwen.eventpulse.entity.RecommendationRequest;
import dev.kaiwen.eventpulse.entity.UserPreference;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.EventDailyMetricRepository;
import dev.kaiwen.eventpulse.repository.EventFavouriteRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.InteractionRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;
import dev.kaiwen.eventpulse.repository.RecommendationRequestRepository;
import dev.kaiwen.eventpulse.outbox.OutboxRelay;
import dev.kaiwen.eventpulse.repository.UserPreferenceRepository;

@Service
public class PlatformService {

    private final EventFavouriteRepository favourites;
    private final InteractionRepository interactions;
    private final EventDailyMetricRepository metrics;
    private final UserPreferenceRepository preferences;
    private final RecommendationRequestRepository recommendationRequests;
    private final NotificationRepository notifications;
    private final EventRepository events;
    private final BookingRepository bookings;
    private final EventService eventService;
    private final InteractionService interactionService;
    private final ConcurrentHashMap<String, CacheEntry> popularCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, SseEmitter> sse = new ConcurrentHashMap<>();
    private long cacheFallbacks;
    private StringRedisTemplate redis;
    private OutboxRelay outboxRelay;

    public PlatformService(
            EventFavouriteRepository favourites,
            InteractionRepository interactions,
            EventDailyMetricRepository metrics,
            UserPreferenceRepository preferences,
            RecommendationRequestRepository recommendationRequests,
            NotificationRepository notifications,
            EventRepository events,
            BookingRepository bookings,
            EventService eventService,
            InteractionService interactionService) {
        this.favourites = favourites;
        this.interactions = interactions;
        this.metrics = metrics;
        this.preferences = preferences;
        this.recommendationRequests = recommendationRequests;
        this.notifications = notifications;
        this.events = events;
        this.bookings = bookings;
        this.eventService = eventService;
        this.interactionService = interactionService;
    }

    @Autowired(required = false)
    public void setRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Autowired(required = false)
    public void setOutboxRelay(OutboxRelay outboxRelay) {
        this.outboxRelay = outboxRelay;
    }

    @Transactional
    public void favourite(Long eventId) {
        Long userId = requireUser();
        eventService.require(eventId);
        if (!favourites.existsByUserIdAndEventId(userId, eventId)) {
            favourites.save(new EventFavourite(userId, eventId));
            interactionService.record(userId, eventId, "SAVE");
        }
    }

    @Transactional
    public void unfavourite(Long eventId) {
        Long userId = requireUser();
        favourites.deleteByUserIdAndEventId(userId, eventId);
        interactionService.record(userId, eventId, "UNSAVE");
    }

    public boolean isFavourite(Long eventId) {
        Long userId = BaseContext.getUserId();
        return userId != null && favourites.existsByUserIdAndEventId(userId, eventId);
    }

    @Transactional
    public void interact(Long eventId, String type) {
        Long userId = requireUser();
        if (!List.of("VIEW", "CLICK", "SAVE", "UNSAVE").contains(type)) {
            throw new BusinessException("客户端只能提交浏览、点击和收藏类行为");
        }
        eventService.require(eventId);
        interactionService.record(userId, eventId, type);
    }

    public List<EventVo> nearby(Double lat, Double lng, Double radiusKm) {
        if (lat == null || lng == null) {
            return eventService.list(null, null, null);
        }
        double radius = radiusKm == null ? 20 : radiusKm;
        return events.findByStatusInOrderByStartsAtAsc(EventStatus.PUBLIC_LIST).stream()
                .filter(event -> event.getLatitude() != null && event.getLongitude() != null)
                .filter(event -> haversine(lat, lng, event.getLatitude(), event.getLongitude()) <= radius)
                .map(eventService::toVo)
                .toList();
    }

    public List<EventVo> recommend() {
        try {
            return recommendInternal();
        }
        catch (Exception e) {
            cacheFallbacks++;
            return popular();
        }
    }

    public List<EventVo> popular() {
        List<EventVo> fromRedis = readPopularFromRedis();
        if (fromRedis != null) {
            return fromRedis;
        }
        CacheEntry cached = popularCache.get("popular");
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.events;
        }
        List<EventVo> fresh = events.findByStatusInOrderByStartsAtAsc(EventStatus.PUBLIC_LIST).stream()
                .sorted(Comparator.comparingInt(Event::getSold).reversed())
                .limit(8)
                .map(eventService::toVo)
                .toList();
        popularCache.put("popular", new CacheEntry(fresh, Instant.now().plusSeconds(30)));
        writePopularToRedis(fresh);
        return fresh;
    }

    private List<EventVo> readPopularFromRedis() {
        if (redis == null) {
            return null;
        }
        try {
            String ids = redis.opsForValue().get("popular:ids");
            if (ids == null || ids.isBlank()) {
                return null;
            }
            List<Long> eventIds = Arrays.stream(ids.split(",")).filter(s -> !s.isBlank()).map(Long::valueOf).toList();
            return eventIds.stream().map(eventService::require).map(eventService::toVo).toList();
        }
        catch (Exception e) {
            cacheFallbacks++;
            return null;
        }
    }

    private void writePopularToRedis(List<EventVo> fresh) {
        if (redis == null) {
            return;
        }
        try {
            String ids = fresh.stream().map(event -> String.valueOf(event.id())).collect(Collectors.joining(","));
            redis.opsForValue().set("popular:ids", ids, Duration.ofSeconds(30));
        }
        catch (Exception e) {
            cacheFallbacks++;
        }
    }

    public List<NotificationVo> myNotifications() {
        Long userId = requireUser();
        return notifications.findAllByOrderByCreatedAtDesc().stream()
                .filter(n -> userId.equals(n.getUserId()) || (n.getUserId() == null && n.getBookingId() != null
                        && bookings.findById(n.getBookingId()).map(b -> userId.equals(b.getUserId())).orElse(false)))
                .map(n -> new NotificationVo(
                        n.getId(), n.getUserId(), n.getEventId(), n.getBookingId(),
                        n.getType(), n.getTitle(), n.getMessage(), n.getPayload(), n.getReadAt(), n.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void markRead(Long id) {
        Long userId = requireUser();
        Notification n = notifications.findById(id).orElseThrow(() -> BusinessException.notFound("消息不存在"));
        if (n.getUserId() != null && !n.getUserId().equals(userId)) {
            throw BusinessException.forbidden("只能阅读自己的消息");
        }
        n.setReadAt(Instant.now());
    }

    public Map<String, Object> dashboard() {
        EventService.requireOrganiser();
        Long organiserId = BaseContext.getUserId();
        List<Event> mine = events.findByOrganiserIdOrderByStartsAtDesc(organiserId);
        long published = mine.stream().filter(e -> EventStatus.PUBLISHED.equals(e.getStatus())).count();
        int sold = mine.stream().mapToInt(Event::getSold).sum();
        int capacity = mine.stream().mapToInt(Event::getCapacity).sum();
        return Map.of(
                "eventCount", mine.size(),
                "publishedCount", published,
                "sold", sold,
                "capacity", capacity,
                "sellThrough", capacity == 0 ? 0d : sold * 100.0 / capacity,
                "lowStock", mine.stream().filter(e -> e.remaining() > 0 && e.remaining() <= 5).map(Event::getTitle).toList(),
                "outboxPending", outboxRelay == null ? 0L : outboxRelay.pending(),
                "outboxFailed", outboxRelay == null ? 0L : outboxRelay.failed(),
                "oldestPendingAgeSeconds", outboxRelay == null ? null : outboxRelay.oldestPendingAgeSeconds(),
                "cacheFallbacks", cacheFallbacks);
    }

    public Map<String, Object> analytics(Long eventId, LocalDate from, LocalDate to) {
        EventService.requireOrganiser();
        Event event = eventId == null ? null : events.findByIdAndOrganiserId(eventId, BaseContext.getUserId())
                .orElseThrow(() -> BusinessException.forbidden("只能查看自己的活动"));
        LocalDate start = from == null ? LocalDate.now().minusDays(14) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        List<EventDailyMetric> rows = event == null
                ? List.of()
                : metrics.findByEventIdAndMetricDateBetween(eventId, start, end);
        int views = rows.stream().mapToInt(EventDailyMetric::getViews).sum();
        int clicks = rows.stream().mapToInt(EventDailyMetric::getClicks).sum();
        int books = rows.stream().mapToInt(EventDailyMetric::getBookings).sum();
        return Map.of(
                "views", views,
                "clicks", clicks,
                "bookings", books,
                "tickets", event == null ? 0 : event.getSold(),
                "conversion", views == 0 ? 0d : books * 100.0 / views,
                "series", rows);
    }

    public void savePreference(String categories, String cities, Double lat, Double lng, Double radiusKm) {
        Long userId = requireUser();
        UserPreference pref = preferences.findById(userId).orElseGet(UserPreference::new);
        pref.setUserId(userId);
        pref.setCategories(categories);
        pref.setCities(cities);
        pref.setLatitude(lat);
        pref.setLongitude(lng);
        pref.setRadiusKm(radiusKm);
        pref.setUpdatedAt(Instant.now());
        preferences.save(pref);
    }

    public SseEmitter subscribe(Long bookingId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        sse.put(bookingId, emitter);
        emitter.onCompletion(() -> sse.remove(bookingId));
        emitter.onTimeout(() -> sse.remove(bookingId));
        return emitter;
    }

    public void emit(Long bookingId, String name, Object data) {
        SseEmitter emitter = sse.get(bookingId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        }
        catch (Exception e) {
            sse.remove(bookingId);
        }
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void advanceLifecycle() {
        Instant now = Instant.now();
        events.findByStatusAndStartsAtLessThanEqual(EventStatus.PUBLISHED, now)
                .forEach(event -> event.setStatus(EventStatus.ONGOING));
        events.findByStatusAndEndsAtLessThanEqual(EventStatus.ONGOING, now)
                .forEach(event -> event.setStatus(EventStatus.FINISHED));
    }

    public long cacheFallbacks() {
        return cacheFallbacks;
    }

    public PageResult<EventVo> myFavourites() {
        Long userId = requireUser();
        List<EventVo> items = favourites.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(fav -> eventService.toVo(eventService.require(fav.getEventId()), true))
                .toList();
        return new PageResult<>(items.size(), items);
    }

    private List<EventVo> recommendInternal() {
        Long userId = BaseContext.getUserId();
        UserPreference pref = userId == null ? null : preferences.findById(userId).orElse(null);
        List<Event> candidates = events.findByStatusInOrderByStartsAtAsc(EventStatus.PUBLIC_LIST);
        List<EventVo> ranked = candidates.stream()
                .sorted((a, b) -> Integer.compare(score(b, pref, userId), score(a, pref, userId)))
                .limit(10)
                .map(event -> eventService.toVo(event, userId != null && favourites.existsByUserIdAndEventId(userId, event.getId())))
                .toList();
        RecommendationRequest request = new RecommendationRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setUserId(userId);
        request.setPartitionKey("default");
        request.setModelVersion("rules-v1");
        request.setFeatureVersion("pref-v1");
        request.setFrozenCandidates(ranked.stream().map(e -> String.valueOf(e.id())).reduce((a, b) -> a + "," + b).orElse(""));
        request.setQueriedAt(Instant.now());
        request.setExpiresAt(Instant.now().plusSeconds(300));
        recommendationRequests.save(request);
        return ranked;
    }

    private int score(Event event, UserPreference pref, Long userId) {
        int score = event.getSold();
        if (pref != null) {
            if (pref.getCategories() != null
                    && pref.getCategories().toLowerCase(Locale.ROOT).contains(event.getCategory().toLowerCase(Locale.ROOT))) {
                score += 20;
            }
            if (pref.getCities() != null && pref.getCities().contains(event.getCity())) {
                score += 15;
            }
        }
        if (userId != null) {
            score += (int) interactions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                    .filter(i -> event.getId().equals(i.getEventId())).count();
        }
        return score;
    }

    private static Long requireUser() {
        Long userId = BaseContext.getUserId();
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        return userId;
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private record CacheEntry(List<EventVo> events, Instant expiresAt) {
    }
}
