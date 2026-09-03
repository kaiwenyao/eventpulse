package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.config.RedisConfig;
import dev.kaiwen.eventpulse.controller.MediaController;
import dev.kaiwen.eventpulse.controller.OrganiserEventController;
import dev.kaiwen.eventpulse.controller.OrganiserOpsController;
import dev.kaiwen.eventpulse.controller.PlatformController;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.domain.TicketStatus;
import dev.kaiwen.eventpulse.dto.EventDtos.ArchiveEventRequest;
import dev.kaiwen.eventpulse.dto.EventDtos.CancelEventRequest;
import dev.kaiwen.eventpulse.dto.EventDtos.OrganiserEventRequest;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.EventAuditLog;
import dev.kaiwen.eventpulse.entity.EventDailyMetric;
import dev.kaiwen.eventpulse.entity.EventFavourite;
import dev.kaiwen.eventpulse.entity.Interaction;
import dev.kaiwen.eventpulse.entity.MediaAsset;
import dev.kaiwen.eventpulse.entity.Notification;
import dev.kaiwen.eventpulse.entity.OutboxEvent;
import dev.kaiwen.eventpulse.entity.Ticket;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.entity.UserPreference;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.outbox.OutboxRelay;
import dev.kaiwen.eventpulse.outbox.OutboxStatusService;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.EventAuditLogRepository;
import dev.kaiwen.eventpulse.repository.EventDailyMetricRepository;
import dev.kaiwen.eventpulse.repository.EventFavouriteRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.InteractionRepository;
import dev.kaiwen.eventpulse.repository.MediaAssetRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;
import dev.kaiwen.eventpulse.repository.OutboxRepository;
import dev.kaiwen.eventpulse.repository.TicketRepository;
import dev.kaiwen.eventpulse.repository.UserPreferenceRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;
import dev.kaiwen.eventpulse.service.BookingService;
import dev.kaiwen.eventpulse.service.EventService;
import dev.kaiwen.eventpulse.service.InteractionService;
import dev.kaiwen.eventpulse.service.MediaService;
import dev.kaiwen.eventpulse.service.OrganiserEventService;
import dev.kaiwen.eventpulse.service.PlatformService;
import dev.kaiwen.eventpulse.service.PopularCache;
import dev.kaiwen.eventpulse.sse.SseConnectionRegistry;
import dev.kaiwen.eventpulse.sse.SseSubscriptionService;
import dev.kaiwen.eventpulse.service.TicketCodes;
import dev.kaiwen.eventpulse.service.TicketService;
import dev.kaiwen.eventpulse.storage.LocalStorageMediaStorage;

@ExtendWith(MockitoExtension.class)
class CrudEnhancementTest {

    @Mock EventRepository events;
    @Mock BookingRepository bookings;
    @Mock TicketRepository tickets;
    @Mock EventAuditLogRepository audits;
    @Mock EventFavouriteRepository favourites;
    @Mock InteractionRepository interactions;
    @Mock EventDailyMetricRepository metrics;
    @Mock UserPreferenceRepository preferences;
    @Mock NotificationRepository notifications;
    @Mock OutboxRepository outboxRepo;
    @Mock UserRepository users;
    @Mock KafkaTemplate<String, String> kafka;

    @AfterEach
    void clear() {
        BaseContext.clear();
    }

    @Test
    void statusTransitionsAndTicketCodes() {
        assertThat(EventStatus.canTransition(EventStatus.DRAFT, EventStatus.PUBLISHED)).isTrue();
        assertThat(EventStatus.canTransition(EventStatus.PUBLISHED, EventStatus.CANCELLED)).isTrue();
        assertThat(EventStatus.canTransition(EventStatus.CANCELLED, EventStatus.PUBLISHED)).isFalse();
        assertThat(EventStatus.canTransition(EventStatus.FINISHED, EventStatus.ARCHIVED)).isTrue();
        assertThat(EventStatus.canTransition(EventStatus.ARCHIVED, EventStatus.DRAFT)).isFalse();
        String raw = TicketCodes.raw();
        assertThat(TicketCodes.hash(raw)).hasSize(64);
        String secret = "test-secret-key-change-me-0123456789ab";
        assertThat(TicketCodes.decrypt(TicketCodes.encrypt(raw, secret), secret)).isEqualTo(raw);
        assertThatThrownBy(() -> TicketCodes.decrypt("%%%", secret)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void organiserLifecycle() {
        EventService eventService = new EventService(events);
        OutboxWriter writer = new OutboxWriter(outboxRepo, new ObjectMapper());
        OrganiserEventService service = new OrganiserEventService(
                events, bookings, tickets, audits, eventService, users, writer, new ObjectMapper(),
                new PopularCache());
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);

        BaseContext.setUserId(2L);
        BaseContext.setRole("ORGANISER");
        Instant start = Instant.now().plusSeconds(86400);
        OrganiserEventRequest req = upsert("夜", start, false);
        when(events.save(any())).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(9L);
            return e;
        });
        assertThat(service.create(req).status()).isEqualTo(EventStatus.DRAFT);
        assertThat(service.create(upsert("夜", start, true)).status()).isEqualTo(EventStatus.PUBLISHED);

        Event draft = event(9L, EventStatus.DRAFT, 2L);
        when(events.findByIdAndOrganiserId(9L, 2L)).thenReturn(Optional.of(draft));
        when(events.findByOrganiserIdOrderByStartsAtDesc(2L)).thenReturn(List.of(draft));
        assertThat(service.list("夜", EventStatus.DRAFT, "music", null, null, "startsAt,asc").getTotal()).isEqualTo(1);
        assertThat(service.get(9L).id()).isEqualTo(9L);
        assertThat(service.publish(9L).status()).isEqualTo(EventStatus.PUBLISHED);
        draft.setStatus(EventStatus.PUBLISHED);
        when(tickets.findByEventIdOrderByIdAsc(9L)).thenReturn(List.of());
        when(bookings.findByEventIdOrderByCreatedAtDesc(9L)).thenReturn(List.of());
        assertThat(service.cancel(9L, new CancelEventRequest("天气")).status()).isEqualTo(EventStatus.CANCELLED);
        draft.setStatus(EventStatus.CANCELLED);
        assertThat(service.archive(9L, new ArchiveEventRequest("备注")).status()).isEqualTo(EventStatus.ARCHIVED);
        Event source = event(3L, EventStatus.FINISHED, 2L);
        when(events.findByIdAndOrganiserId(3L, 2L)).thenReturn(Optional.of(source));
        assertThat(service.duplicate(3L).title()).contains("copy");
        Event deletable = event(4L, EventStatus.DRAFT, 2L);
        when(events.findByIdAndOrganiserId(4L, 2L)).thenReturn(Optional.of(deletable));
        service.delete(4L);
        verify(events).delete(deletable);
        Event published = event(5L, EventStatus.PUBLISHED, 2L);
        when(events.findByIdAndOrganiserId(5L, 2L)).thenReturn(Optional.of(published));
        assertThat(service.update(5L, upsert("改", start, false)).title()).isEqualTo("改");
        assertThatThrownBy(() -> service.update(5L, new OrganiserEventRequest(
                "改", "s", "d", "art", null, null, start, start.plusSeconds(3600),
                "Beijing", "v", "a", null, null, 1, 10, null, start, 2, "c", "n", 99L, false)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(new OrganiserEventRequest(
                "坏", null, null, "music", null, null, start, start.minusSeconds(10),
                "Shanghai", null, null, null, null, 1, 10, null, null, 1, null, null, null, false)))
                .isInstanceOf(BusinessException.class);
        assertThat(service.allowedActions(event(1L, EventStatus.DRAFT, 2L))).contains("publish", "delete");
        assertThat(service.allowedActions(event(1L, EventStatus.PUBLISHED, 2L))).contains("cancel");
        assertThat(service.allowedActions(event(1L, EventStatus.ONGOING, 2L))).contains("attendees");
        assertThat(service.allowedActions(event(1L, EventStatus.FINISHED, 2L))).contains("archive");
        when(events.findByIdAndOrganiserId(8L, 2L)).thenReturn(Optional.empty());
        when(events.findById(8L)).thenReturn(Optional.of(event(8L, EventStatus.DRAFT, 99L)));
        assertThatThrownBy(() -> service.requireOwn(8L)).isInstanceOf(BusinessException.class);
        when(events.findByIdAndOrganiserId(77L, 2L)).thenReturn(Optional.empty());
        when(events.findById(77L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireOwn(77L)).isInstanceOf(BusinessException.class);
        Event archived = event(12L, EventStatus.ARCHIVED, 2L);
        when(events.findByIdAndOrganiserId(12L, 2L)).thenReturn(Optional.of(archived));
        assertThatThrownBy(() -> service.publish(12L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.cancel(12L, new CancelEventRequest("x"))).isInstanceOf(BusinessException.class);
        Event draftWithOrders = event(13L, EventStatus.DRAFT, 2L);
        when(events.findByIdAndOrganiserId(13L, 2L)).thenReturn(Optional.of(draftWithOrders));
        Booking existing = new Booking();
        existing.setEventId(13L);
        when(bookings.findByEventIdOrderByCreatedAtDesc(13L)).thenReturn(List.of(existing));
        assertThatThrownBy(() -> service.delete(13L)).isInstanceOf(BusinessException.class);
        assertThat(service.allowedActions(event(1L, EventStatus.ARCHIVED, 2L))).contains("view");
        assertThat(service.allowedActions(event(1L, EventStatus.CANCELLED, 2L))).contains("archive");
        new OrganiserEventController(service).list(null, null, null, null, null, null);
    }

    @Test
    void ticketsOutboxAndPlatform() {
        AppProperties props = new AppProperties();
        props.setSecretKey("test-secret-key-change-me-0123456789ab");
        EventService eventService = new EventService(events);
        OrganiserEventService organiser = new OrganiserEventService(
                events, bookings, tickets, audits, eventService, users, new OutboxWriter(outboxRepo, new ObjectMapper()),
                new ObjectMapper(), new PopularCache());
        TicketService ticketService = new TicketService(tickets, organiser, props);
        when(tickets.save(any())).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });
        BaseContext.setUserId(2L);
        BaseContext.setRole("ORGANISER");
        Ticket issued = ticketService.issue(10L, 20L, 1).getFirst();
        String code = ticketService.reveal(issued);
        when(tickets.findByTicketCodeHash(TicketCodes.hash(code))).thenReturn(Optional.of(issued));
        when(tickets.findByTicketCodeHashForUpdate(TicketCodes.hash(code))).thenReturn(Optional.of(issued));
        when(events.findByIdAndOrganiserId(20L, 2L)).thenReturn(Optional.of(event(20L, EventStatus.PUBLISHED, 2L)));
        assertThat(ticketService.checkIn(code, "manual").getStatus()).isEqualTo(TicketStatus.CHECKED_IN);
        assertThatThrownBy(() -> ticketService.checkIn(code, "manual")).isInstanceOf(BusinessException.class);
        when(tickets.findById(1L)).thenReturn(Optional.of(issued));
        assertThat(ticketService.undoCheckIn(1L, "误核").getStatus()).isEqualTo(TicketStatus.VALID);
        when(tickets.findByBookingIdForUpdate(10L)).thenReturn(List.of(issued));
        ticketService.cancelForBooking(10L);
        assertThat(ticketService.toView(issued, true).code()).isEqualTo(code);

        new OutboxWriter(outboxRepo, new ObjectMapper())
                .write("booking-events", "BOOKING_CREATED", "booking:1", "k1",
                        java.util.Map.of("type", "BOOKING_CREATED"));
        OutboxEvent pending = new OutboxEvent();
        pending.setId(1L);
        pending.setTopic("booking-events");
        pending.setDedupKey("k1");
        pending.setMessageKey("booking:1");
        pending.setPayload("{}");
        pending.setEventType("BOOKING_CREATED");
        OutboxStatusService status = new OutboxStatusService(outboxRepo);
        when(outboxRepo.claimBatch(anyString(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);
        when(outboxRepo.findByClaimedByOrderByIdAsc(anyString())).thenReturn(List.of(pending));
        when(outboxRepo.countByPublishedAtIsNullAndFailedAtIsNull()).thenReturn(1L);
        when(outboxRepo.countByFailedAtIsNotNull()).thenReturn(0L);
        OutboxRelay relay = new OutboxRelay(outboxRepo, kafka, status, 12L, 50, 60);
        relay.publish();
        assertThat(status.pending()).isEqualTo(1);
        assertThat(status.failed()).isZero();
        KafkaTemplate<String, String> failing = org.mockito.Mockito.mock(KafkaTemplate.class);
        when(failing.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("down"));
        new OutboxRelay(outboxRepo, failing, status, 12L, 50, 60).publish();

        PopularCache popularCache = new PopularCache();
        PlatformService platform = new PlatformService(
                favourites, interactions, metrics, preferences, notifications, events, bookings, eventService,
                new InteractionService(interactions, metrics), popularCache);
        when(events.findById(20L)).thenReturn(Optional.of(event(20L, EventStatus.PUBLISHED, 2L)));
        when(favourites.existsByUserIdAndEventId(2L, 20L)).thenReturn(false);
        platform.favourite(20L);
        platform.unfavourite(20L);
        platform.interact(20L, "VIEW");
        platform.interact(20L, "CLICK");
        assertThatThrownBy(() -> platform.interact(20L, "BOOK")).isInstanceOf(BusinessException.class);
        Event noGeo = event(21L, EventStatus.PUBLISHED, 2L);
        noGeo.setLatitude(null);
        noGeo.setLongitude(null);
        when(events.findByStatusInOrderByStartsAtAsc(any())).thenReturn(List.of(event(20L, EventStatus.PUBLISHED, 2L), noGeo));
        assertThat(platform.nearby(31.2, 121.5, 50d)).isNotEmpty();
        assertThat(platform.nearby(null, null, null)).isNotNull();
        UserPreference pref = new UserPreference();
        pref.setCategories("music");
        pref.setCities("Shanghai");
        when(preferences.findById(2L)).thenReturn(Optional.of(pref));
        assertThat(platform.popular()).isNotEmpty();
        when(events.findByOrganiserIdOrderByStartsAtDesc(2L)).thenReturn(List.of(event(20L, EventStatus.PUBLISHED, 2L)));
        platform.setOutboxStatus(status);
        assertThat(platform.dashboard().get("eventCount")).isEqualTo(1);
        when(events.findByIdAndOrganiserId(20L, 2L)).thenReturn(Optional.of(event(20L, EventStatus.PUBLISHED, 2L)));
        when(metrics.findByEventIdAndMetricDateBetween(any(), any(), any())).thenReturn(List.of());
        assertThat(platform.analytics(20L, LocalDate.now().minusDays(1), LocalDate.now()).get("views")).isEqualTo(0);
        assertThat(platform.analytics(null, null, null).get("views")).isEqualTo(0);
        EventDailyMetric row = new EventDailyMetric();
        row.setViews(4);
        row.setClicks(2);
        row.setBookings(1);
        when(metrics.findByEventIdAndMetricDateBetween(any(), any(), any())).thenReturn(List.of(row));
        assertThat(platform.analytics(20L, null, null).get("views")).isEqualTo(4);
        assertThat(platform.isFavourite(20L)).isFalse();
        platform.savePreference("music", "Shanghai", 31.2, 121.5, 10d);
        Notification n = new Notification(1L, "hi");
        n.setId(3L);
        n.setUserId(2L);
        when(notifications.findById(3L)).thenReturn(Optional.of(n));
        when(notifications.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(n));
        assertThat(platform.myNotifications()).hasSize(1);
        platform.markRead(3L);
        Notification other = new Notification(2L, "no");
        other.setId(4L);
        other.setUserId(99L);
        when(notifications.findById(4L)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> platform.markRead(4L)).isInstanceOf(BusinessException.class);
        when(favourites.findByUserIdOrderByCreatedAtDesc(2L)).thenReturn(List.of(new EventFavourite(2L, 20L)));
        assertThat(platform.myFavourites().getTotal()).isEqualTo(1);
        org.springframework.data.redis.core.StringRedisTemplate redis =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        // Redis 缓存命中 / 缓存内容损坏 / 未命中：接口都必须正常返回（回源数据库）。
        when(values.get("popular:ids")).thenReturn("not-a-number");
        popularCache.setRedis(redis);
        assertThat(platform.popular()).isNotEmpty();
        when(values.get("popular:ids")).thenReturn("");
        assertThat(platform.popular()).isNotEmpty();
        when(values.get("popular:ids")).thenReturn("20");
        when(events.findById(20L)).thenReturn(Optional.of(event(20L, EventStatus.PUBLISHED, 2L)));
        assertThat(platform.popular()).isNotEmpty();
        popularCache.evict();
        org.mockito.Mockito.verify(redis).delete("popular:ids");
        // SSE 订阅所有权：本人可订，他人不可订。
        Booking ownBooking = new Booking();
        ownBooking.setId(1L);
        ownBooking.setUserId(2L);
        when(bookings.findById(1L)).thenReturn(Optional.of(ownBooking));
        SseSubscriptionService sse = new SseSubscriptionService(
                new SseConnectionRegistry(1000, 5, 20), bookings, events);
        assertThat(sse.subscribe(1L)).isNotNull();
        BaseContext.setUserId(99L);
        assertThatThrownBy(() -> sse.subscribe(1L)).isInstanceOf(BusinessException.class);
        BaseContext.setUserId(2L);
        PlatformController api = new PlatformController(platform, sse);
        api.favourite(20L);
        api.unfavourite(20L);
        api.favourites();
        api.interact(java.util.Map.of("eventId", 20, "type", "CLICK"));
        api.nearby(31.2, 121.5, 10d);
        api.dashboard();
        api.analytics(20L, LocalDate.now().minusDays(1), LocalDate.now());
        api.eventAnalytics(20L, null, null);
        api.stream(1L);
        api.preferences(java.util.Map.of("categories", "music", "cities", "Shanghai", "latitude", 31.2, "longitude", 121.5, "radiusKm", 8));
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        OrganiserEventController orgApi = new OrganiserEventController(organiser);
        orgApi.create(upsert("夜", Instant.now().plusSeconds(86400), false));
        orgApi.get(20L);
        Event draft20 = event(20L, EventStatus.DRAFT, 2L);
        when(events.findByIdAndOrganiserId(20L, 2L)).thenReturn(Optional.of(draft20));
        orgApi.publish(20L);
        orgApi.update(20L, upsert("改", Instant.now().plusSeconds(86400), false));
        orgApi.duplicate(20L);
        orgApi.cancel(20L, new CancelEventRequest("原因"));
        Event finished = event(20L, EventStatus.FINISHED, 2L);
        when(events.findByIdAndOrganiserId(20L, 2L)).thenReturn(Optional.of(finished));
        orgApi.archive(20L, new ArchiveEventRequest("归档"));
        Event leftover = event(21L, EventStatus.DRAFT, 2L);
        when(events.findByIdAndOrganiserId(21L, 2L)).thenReturn(Optional.of(leftover));
        orgApi.delete(21L);
        EventService eventServiceForOps = new EventService(events);
        BookingService bookingService = new BookingService(bookings, eventServiceForOps, events, ticketService, tickets, users,
                new OutboxWriter(outboxRepo, new ObjectMapper()), new PopularCache());
        OrganiserOpsController ops = new OrganiserOpsController(organiser, ticketService, bookings, users, bookingService);
        Booking confirmed = new Booking();
        confirmed.setId(30L);
        confirmed.setUserId(2L);
        confirmed.setEventId(20L);
        confirmed.setQuantity(1);
        confirmed.setStatus("CONFIRMED");
        confirmed.setCreatedAt(Instant.now());
        when(bookings.findByEventIdOrderByCreatedAtDesc(20L)).thenReturn(List.of(confirmed));
        when(events.findById(20L)).thenReturn(Optional.of(event(20L, EventStatus.PUBLISHED, 2L)));
        when(tickets.countByBookingIdAndStatus(any(), any())).thenReturn(0L);
        when(tickets.findByBookingIdOrderByIdAsc(30L)).thenReturn(List.of());
        when(users.findById(2L)).thenReturn(Optional.of(new User()));
        ops.eventBookings(20L);
        ops.attendees(20L);
        ops.attendeesCsv(20L);
        issued.setStatus(TicketStatus.VALID);
        ops.lookup(code);
        ops.checkIn(new dev.kaiwen.eventpulse.dto.BookingDtos.CheckInRequest(code, "scan"));
        issued.setStatus(TicketStatus.CHECKED_IN);
        ops.undo(1L, new dev.kaiwen.eventpulse.dto.BookingDtos.UndoCheckInRequest("误"));
        Event ongoing = event(6L, EventStatus.ONGOING, 2L);
        when(events.findByIdAndOrganiserId(6L, 2L)).thenReturn(Optional.of(ongoing));
        organiser.update(6L, upsert("进行中改", Instant.now().plusSeconds(86400), false));
        Event done = event(7L, EventStatus.FINISHED, 2L);
        when(events.findByIdAndOrganiserId(7L, 2L)).thenReturn(Optional.of(done));
        organiser.update(7L, upsert("结束改", Instant.now().plusSeconds(86400), false));
        mediaAndRedis();
        touchEntities();
    }

    private void mediaAndRedis() {
        AppProperties props = new AppProperties();
        props.setMediaDir(System.getProperty("java.io.tmpdir") + "/ep-media-" + System.nanoTime());
        MediaAssetRepository mediaRepo = org.mockito.Mockito.mock(MediaAssetRepository.class);
        MediaService media = new MediaService(mediaRepo, new LocalStorageMediaStorage(props));
        BaseContext.setUserId(2L);
        when(mediaRepo.save(any())).thenAnswer(inv -> {
            MediaAsset asset = inv.getArgument(0);
            asset.setId(5L);
            return asset;
        });
        MediaAsset uploaded = media.upload("封面.png", "image/png", new byte[] {1, 2, 3});
        MediaAsset jpeg = media.upload(null, "image/jpeg", new byte[] {1, 2});
        assertThat(jpeg.getContentType()).isEqualTo("image/jpeg");
        media.upload("x.webp", "image/webp", new byte[] {1, 2, 3, 4});
        assertThat(uploaded.getPublicUrl()).contains("/api/media/images/5");
        uploaded.setStatus("ACTIVE");
        uploaded.setStorageKey(uploaded.getStorageKey());
        when(mediaRepo.findById(5L)).thenReturn(Optional.of(uploaded));
        assertThat(media.requireActive(5L).getId()).isEqualTo(5L);
        assertThat(media.readBytes(uploaded)).hasSize(3);
        MediaController mediaApi = new MediaController(media);
        org.springframework.mock.web.MockMultipartFile part =
                new org.springframework.mock.web.MockMultipartFile("file", "a.png", "image/png", new byte[] {9, 8, 7});
        mediaApi.upload(part);
        mediaApi.get(5L);
        media.delete(5L);
        assertThat(uploaded.getStatus()).isEqualTo("DELETED");
        uploaded.setStatus("ACTIVE");
        mediaApi.delete(5L);
        BaseContext.clear();
        assertThatThrownBy(() -> media.upload("x", "image/png", new byte[] {1})).isInstanceOf(BusinessException.class);
        BaseContext.setUserId(2L);
        assertThatThrownBy(() -> media.upload("x", "image/png", new byte[3 * 1024 * 1024])).isInstanceOf(BusinessException.class);
        uploaded.setStatus("DELETED");
        assertThatThrownBy(() -> media.requireActive(5L)).isInstanceOf(BusinessException.class);
        uploaded.setStatus("ACTIVE");
        BaseContext.setUserId(99L);
        assertThatThrownBy(() -> media.delete(5L)).isInstanceOf(BusinessException.class);
        BaseContext.setUserId(2L);
        AppProperties redisProps = new AppProperties();
        redisProps.setRedisHost("localhost");
        redisProps.setRedisPort(6379);
        RedisConfig redisConfig = new RedisConfig();
        assertThat(redisConfig.redisConnectionFactory(redisProps)).isNotNull();
        assertThat(redisConfig.stringRedisTemplate(redisConfig.redisConnectionFactory(redisProps))).isNotNull();
        assertThatThrownBy(() -> media.upload("x", "text/plain", new byte[] {1}))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> media.upload("x", "image/png", new byte[0]))
                .isInstanceOf(BusinessException.class);
    }

    private static OrganiserEventRequest upsert(String title, Instant start, boolean publish) {
        return new OrganiserEventRequest(
                title, "摘要", "介绍", "music", null, null, start, start.plusSeconds(7200),
                "Shanghai", "场馆", "地址", 31.2, 121.5, 9900, 80, null, start, 4, "联系", "须知", null, publish);
    }

    private static Event event(Long id, String status, Long organiserId) {
        Event event = new Event();
        event.setId(id);
        event.setTitle("夜");
        event.setSummary("s");
        event.setDescription("d");
        event.setCategory("music");
        event.setCity("Shanghai");
        event.setVenueName("场馆");
        event.setAddress("地址");
        event.setLatitude(31.2);
        event.setLongitude(121.4);
        Instant start = Instant.now().plusSeconds(86400);
        event.setStartsAt(start);
        event.setEndsAt(start.plusSeconds(3600));
        event.setPriceCents(100);
        event.setCapacity(10);
        event.setSold(0);
        event.setMaxQuantityPerBooking(4);
        event.setOrganiserId(organiserId);
        event.setStatus(status);
        event.setCreatedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        event.setCoverUrl("/x");
        event.setContactInfo("c");
        event.setAttendanceNotes("n");
        return event;
    }

    private static void touchEntities() {
        Booking b = new Booking();
        b.setCancelledAt(Instant.now());
        b.setOrganiserNote("note");
        assertThat(b.getCancelledAt()).isNotNull();
        Notification n = new Notification();
        n.setUserId(1L);
        n.setEventId(2L);
        n.setType("BOOKING");
        n.setTitle("t");
        n.setPayload("{}");
        n.setDedupKey("k");
        n.setReadAt(Instant.now());
        assertThat(n.getDedupKey()).isEqualTo("k");
        Ticket t = new Ticket();
        t.setId(1L);
        t.setBookingId(2L);
        t.setEventId(3L);
        t.setTicketCodeHash("h");
        t.setTicketCodeCipher("c");
        t.setStatus(TicketStatus.VALID);
        t.setCheckedInAt(Instant.now());
        t.setCheckedInBy(1L);
        t.setCheckInSource("manual");
        t.setRevokedAt(Instant.now());
        t.setRevokedBy(1L);
        t.setRevocationReason("x");
        t.setCreatedAt(Instant.now());
        assertThat(t.getCheckInSource()).isEqualTo("manual");
        EventFavourite f = new EventFavourite(1L, 2L);
        f.setCreatedAt(Instant.now());
        assertThat(f.getEventId()).isEqualTo(2L);
        assertThat(new EventFavourite.Key()).isNotEqualTo(new EventFavourite.Key(1L, 2L));
        assertThat(new EventFavourite.Key(1L, 2L)).isEqualTo(new EventFavourite.Key(1L, 2L));
        assertThat(new EventFavourite.Key(1L, 2L)).isNotEqualTo(new EventFavourite.Key(2L, 1L));
        assertThat(new EventFavourite.Key(1L, 2L)).isNotEqualTo("x");
        assertThat(new EventFavourite.Key(1L, 2L).hashCode()).isEqualTo(new EventFavourite.Key(1L, 2L).hashCode());
        EventFavourite fav = new EventFavourite();
        fav.setUserId(1L);
        fav.setEventId(2L);
        fav.setCreatedAt(Instant.now());
        assertThat(fav.getUserId()).isEqualTo(1L);
        EventAuditLog log = new EventAuditLog();
        log.setId(1L);
        log.setEventId(2L);
        log.setOperatorId(3L);
        log.setAction("PUBLISH");
        log.setBeforeData("{}");
        log.setAfterData("{}");
        log.setCreatedAt(Instant.now());
        assertThat(log.getAfterData()).isEqualTo("{}");
        MediaAsset media = new MediaAsset();
        media.setId(1L);
        media.setOwnerId(2L);
        media.setStorageKey("k");
        media.setPublicUrl("/m");
        media.setContentType("image/png");
        media.setSizeBytes(10);
        media.setStatus("ACTIVE");
        media.setCreatedAt(Instant.now());
        media.setDeletedAt(Instant.now());
        assertThat(media.getSizeBytes()).isEqualTo(10);
        OutboxEvent o = new OutboxEvent();
        o.setId(1L);
        o.setTopic("t");
        o.setEventType("e");
        o.setPayload("{}");
        o.setDedupKey("d");
        o.setCreatedAt(Instant.now());
        o.setPublishedAt(Instant.now());
        assertThat(o.getPublishedAt()).isNotNull();
        Interaction i = new Interaction();
        i.setId(1L);
        i.setUserId(2L);
        i.setEventId(3L);
        i.setType("VIEW");
        i.setCreatedAt(Instant.now());
        assertThat(i.getUserId()).isEqualTo(2L);
        UserPreference p = new UserPreference();
        p.setUserId(1L);
        p.setCategories("music");
        p.setCities("Shanghai");
        p.setLatitude(1d);
        p.setLongitude(2d);
        p.setRadiusKm(3d);
        p.setUpdatedAt(Instant.now());
        assertThat(p.getRadiusKm()).isEqualTo(3d);
        EventDailyMetric m = new EventDailyMetric();
        m.setEventId(1L);
        m.setMetricDate(LocalDate.now());
        m.setViews(1);
        m.setClicks(1);
        m.setSaves(1);
        m.setUnsaves(1);
        m.setBookings(1);
        m.setTickets(1);
        m.setCancels(1);
        m.setCheckIns(1);
        assertThat(m.getCheckIns()).isEqualTo(1);
        // AI 会话 / 消息 / 调用日志实体的字段与回调。
        dev.kaiwen.eventpulse.entity.AiConversation conversation = new dev.kaiwen.eventpulse.entity.AiConversation();
        conversation.setUserId(1L);
        conversation.setKind("discovery");
        assertThat(conversation.getUserId()).isEqualTo(1L);
        assertThat(conversation.getKind()).isEqualTo("discovery");
        assertThat(conversation.getCreatedAt()).isNotNull();
        assertThat(conversation.getCreatedAt()).isNotNull();
        assertThat(conversation.getUpdatedAt()).isNotNull();
        dev.kaiwen.eventpulse.entity.AiMessage message = new dev.kaiwen.eventpulse.entity.AiMessage();
        message.setConversationId(1L);
        message.setRole(dev.kaiwen.eventpulse.entity.AiMessage.ROLE_USER);
        message.setContent("hi");
        assertThat(message.getConversationId()).isEqualTo(1L);
        assertThat(message.getRole()).isEqualTo("user");
        assertThat(message.getContent()).isEqualTo("hi");
        assertThat(message.getCreatedAt()).isNotNull();
        dev.kaiwen.eventpulse.entity.AiRequestLog aiLog = new dev.kaiwen.eventpulse.entity.AiRequestLog();
        aiLog.setRequestId("rid");
        aiLog.setUserId(1L);
        aiLog.setFeature("discovery");
        aiLog.setProvider("openai");
        aiLog.setModelName("m");
        aiLog.setStatus("success");
        aiLog.setErrorCode(null);
        aiLog.setLatencyMs(12);
        aiLog.setInputTokens(3);
        aiLog.setOutputTokens(5);
        assertThat(aiLog.getRequestId()).isEqualTo("rid");
        assertThat(aiLog.getFeature()).isEqualTo("discovery");
        assertThat(aiLog.getProvider()).isEqualTo("openai");
        assertThat(aiLog.getModelName()).isEqualTo("m");
        assertThat(aiLog.getStatus()).isEqualTo("success");
        assertThat(aiLog.getErrorCode()).isNull();
        assertThat(aiLog.getLatencyMs()).isEqualTo(12);
        assertThat(aiLog.getInputTokens()).isEqualTo(3);
        assertThat(aiLog.getOutputTokens()).isEqualTo(5);
        assertThat(aiLog.getCreatedAt()).isNotNull();
        assertThat(new EventDailyMetric.Key()).isNotEqualTo(new EventDailyMetric.Key(1L, LocalDate.now()));
        assertThat(new EventDailyMetric.Key(1L, LocalDate.now())).isNotEqualTo("x");
        assertThat(new EventDailyMetric.Key(1L, LocalDate.now()).hashCode()).isNotZero();
        User u = new User();
        u.setName("n");
        assertThat(u.getName()).isEqualTo("n");
    }
}
