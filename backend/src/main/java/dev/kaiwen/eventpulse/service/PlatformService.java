package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import dev.kaiwen.eventpulse.outbox.OutboxStatusService;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.EventDailyMetricRepository;
import dev.kaiwen.eventpulse.repository.EventFavouriteRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.InteractionRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;
import dev.kaiwen.eventpulse.repository.RecommendationRequestRepository;
import dev.kaiwen.eventpulse.repository.UserPreferenceRepository;

/**
 * 平台侧服务：收藏、互动、推荐、热门、通知与主办方数据。
 *
 * 没有任何业务级 JVM 内存状态：热门活动只缓存到 Redis（多实例共享），
 * 缓存降级次数走 Micrometer 指标（监控系统里按实例查看与汇总），
 * Redis 不可用时直接回源 PostgreSQL，接口照常可用。
 */
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
    private final PopularCache popularCache;
    private OutboxStatusService outboxStatus;

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
            InteractionService interactionService,
            PopularCache popularCache) {
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
        this.popularCache = popularCache;
    }

    @Autowired(required = false)
    public void setOutboxStatus(OutboxStatusService outboxStatus) {
        this.outboxStatus = outboxStatus;
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
            throw new BusinessException("Clients can only submit view, click, and favourite actions");
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
            return popular();
        }
    }

    /**
     * 热门活动：先读 Redis（所有实例共享），未命中或 Redis 不可用时回源
     * PostgreSQL；Redis 可用则写回缓存。请求落到哪个实例结果都一致。
     */
    public List<EventVo> popular() {
        List<Long> cachedIds = popularCache.readIds();
        if (cachedIds != null) {
            List<EventVo> cached = cachedIds.stream()
                    .map(events::findById)
                    .flatMap(java.util.Optional::stream)
                    .map(eventService::toVo)
                    .toList();
            if (!cached.isEmpty()) {
                return cached;
            }
        }
        List<Event> fresh = events.findByStatusInOrderByStartsAtAsc(EventStatus.PUBLIC_LIST).stream()
                .sorted(Comparator.comparingInt(Event::getSold).reversed())
                .limit(8)
                .toList();
        popularCache.writeIds(fresh.stream().map(Event::getId).toList());
        return fresh.stream().map(eventService::toVo).toList();
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
        Notification n = notifications.findById(id).orElseThrow(() -> BusinessException.notFound("Notification not found"));
        if (n.getUserId() != null && !n.getUserId().equals(userId)) {
            throw BusinessException.forbidden("You can only read your own notifications");
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
                "outboxPending", outboxStatus == null ? 0L : outboxStatus.pending(),
                "outboxFailed", outboxStatus == null ? 0L : outboxStatus.failed(),
                "oldestPendingAgeSeconds", outboxStatus == null ? null : outboxStatus.oldestPendingAgeSeconds());
    }

    public Map<String, Object> analytics(Long eventId, LocalDate from, LocalDate to) {
        EventService.requireOrganiser();
        Event event = eventId == null ? null : events.findByIdAndOrganiserId(eventId, BaseContext.getUserId())
                .orElseThrow(() -> BusinessException.forbidden("You can only view your own events"));
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
            throw new BusinessException("Please sign in");
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
}
