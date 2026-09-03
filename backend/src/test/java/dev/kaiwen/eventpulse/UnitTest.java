package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.config.OpenApiConfig;
import dev.kaiwen.eventpulse.config.PasswordConfig;
import dev.kaiwen.eventpulse.config.WebMvcConfig;
import dev.kaiwen.eventpulse.controller.AuthController;
import dev.kaiwen.eventpulse.controller.BookingController;
import dev.kaiwen.eventpulse.controller.EventController;
import dev.kaiwen.eventpulse.controller.NotificationController;
import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.PageResult;
import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.domain.TicketStatus;
import dev.kaiwen.eventpulse.dto.AuthDtos.LoginRequest;
import dev.kaiwen.eventpulse.dto.AuthDtos.RegisterRequest;
import dev.kaiwen.eventpulse.dto.AuthDtos.WalletRechargeRequest;
import dev.kaiwen.eventpulse.dto.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.dto.EventDtos.EventRequest;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.Interaction;
import dev.kaiwen.eventpulse.entity.Notification;
import dev.kaiwen.eventpulse.entity.Ticket;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.exception.GlobalExceptionHandler;
import dev.kaiwen.eventpulse.interceptor.JwtInterceptor;
import dev.kaiwen.eventpulse.interceptor.RequestLoggingInterceptor;
import dev.kaiwen.eventpulse.kafka.BookingConsumer;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.ConsumedEventRepository;
import dev.kaiwen.eventpulse.repository.EventDailyMetricRepository;
import dev.kaiwen.eventpulse.repository.InteractionRepository;
import dev.kaiwen.eventpulse.repository.TicketRepository;
import dev.kaiwen.eventpulse.repository.EventFavouriteRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;
import dev.kaiwen.eventpulse.service.AuthService;
import dev.kaiwen.eventpulse.service.BookingService;
import dev.kaiwen.eventpulse.service.EventService;
import dev.kaiwen.eventpulse.service.InteractionService;
import dev.kaiwen.eventpulse.service.JwtService;
import dev.kaiwen.eventpulse.service.PlatformService;
import dev.kaiwen.eventpulse.service.PopularCache;
import dev.kaiwen.eventpulse.service.TicketService;

@ExtendWith(MockitoExtension.class)
class UnitTest {

    @Mock
    UserRepository users;
    @Mock
    EventRepository events;
    @Mock
    BookingRepository bookings;
    @Mock
    NotificationRepository notifications;
    @Mock
    EventFavouriteRepository favourites;
    @Mock
    TicketRepository tickets;
    @Mock
    TicketService ticketService;
    @Mock
    OutboxWriter outbox;
    @Mock
    PlatformService platform;
    @Mock
    ConsumedEventRepository consumedEvents;
    @Mock
    InteractionRepository interactionRepo;
    @Mock
    EventDailyMetricRepository metricsRepo;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    KafkaTemplate<String, String> kafkaTemplate;

    @AfterEach
    void clear() {
        BaseContext.clear();
    }

    @Test
    void resultAndPageAndContextAndProperties() {
        assertThat(Result.success().getCode()).isEqualTo(1);
        Result<String> ok = Result.success("x");
        ok.setCode(1);
        ok.setMsg("m");
        ok.setData("d");
        assertThat(ok.getMsg()).isEqualTo("m");
        assertThat(ok.getData()).isEqualTo("d");
        assertThat(Result.error("e").getCode()).isEqualTo(0);

        PageResult<String> page = new PageResult<>();
        page.setTotal(2);
        page.setRecords(List.of("a", "b"));
        assertThat(new PageResult<>(1, List.of("z")).getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).containsExactly("a", "b");

        BaseContext.setUserId(9L);
        BaseContext.setRole("USER");
        assertThat(BaseContext.getUserId()).isEqualTo(9L);
        assertThat(BaseContext.getRole()).isEqualTo("USER");
        BaseContext.clear();
        assertThat(BaseContext.getUserId()).isNull();

        AppProperties props = new AppProperties();
        props.setSecretKey("k".repeat(32));
        props.setTokenTtlMs(1000);
        props.setCorsOrigins("http://localhost:3000,http://localhost:5173");
        props.setMediaDir("data/media");
        props.setRedisEnabled(false);
        props.setRedisHost("localhost");
        props.setRedisPort(6379);
        assertThat(props.getSecretKey()).hasSize(32);
        assertThat(props.getTokenTtlMs()).isEqualTo(1000);
        assertThat(props.corsOriginArray()).contains("http://localhost:3000");
        assertThat(props.getMediaDir()).isEqualTo("data/media");
        assertThat(props.isRedisEnabled()).isFalse();
        assertThat(props.getRedisHost()).isEqualTo("localhost");
        assertThat(props.getRedisPort()).isEqualTo(6379);
    }

    @Test
    void jwtRoundTripAndAuth() {
        AppProperties props = new AppProperties();
        props.setSecretKey("test-secret-key-change-me-0123456789ab");
        JwtService jwt = new JwtService(props);
        String token = jwt.createToken(3L, "ORGANISER");
        assertThat(jwt.parseToken(token).get("userId", Number.class).longValue()).isEqualTo(3L);

        AuthService auth = new AuthService(users, passwordEncoder, jwt, bookings, tickets, favourites, notifications);
        when(users.existsByEmail("a@b.c")).thenReturn(true);
        assertThatThrownBy(() -> auth.register(new RegisterRequest("a@b.c", "123456", "A")))
                .isInstanceOf(BusinessException.class);

        when(users.existsByEmail("n@b.c")).thenReturn(false);
        when(passwordEncoder.encode("123456")).thenReturn("hash");
        when(users.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        assertThat(auth.register(new RegisterRequest("n@b.c", "123456", "N")).user().role()).isEqualTo("USER");

        when(users.findByEmail("missing@b.c")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> auth.login(new LoginRequest("missing@b.c", "x")))
                .isInstanceOf(BusinessException.class);

        User stored = user(2L, "u@b.c", "hash", "USER");
        when(users.findByEmail("u@b.c")).thenReturn(Optional.of(stored));
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);
        assertThatThrownBy(() -> auth.login(new LoginRequest("u@b.c", "bad")))
                .isInstanceOf(BusinessException.class);
        when(passwordEncoder.matches("ok", "hash")).thenReturn(true);
        assertThat(auth.login(new LoginRequest("u@b.c", "ok")).user().id()).isEqualTo(2L);

        when(users.findById(2L)).thenReturn(Optional.of(stored));
        assertThat(auth.me(2L).email()).isEqualTo("u@b.c");
        when(users.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> auth.me(99L)).isInstanceOf(BusinessException.class);

        // 个人中心：余额 + 账户统计 + 演示充值。
        Booking paidBooking = booking(50L, 2L, 1L, 1, "CONFIRMED");
        when(bookings.findByUserIdOrderByCreatedAtDesc(2L)).thenReturn(List.of(paidBooking));
        when(favourites.countByUserId(2L)).thenReturn(2L);
        when(notifications.countByUserId(2L)).thenReturn(3L);
        assertThat(auth.profile(2L).walletCents()).isEqualTo(0);
        assertThat(auth.profile(2L).totalSpentCents()).isEqualTo(100);
        assertThat(auth.profile(2L).favouriteCount()).isEqualTo(2);
        assertThat(auth.profile(2L).notificationCount()).isEqualTo(3);
        WalletRechargeRequest recharge = new WalletRechargeRequest(50000);
        when(users.rechargeWalletWithinLimit(2L, 50000L, 10_000_000_000L)).thenAnswer(inv -> {
            stored.setWalletCents(stored.getWalletCents() + inv.getArgument(1, Long.class));
            return 1;
        });
        assertThat(auth.recharge(2L, recharge).walletCents()).isEqualTo(50000);
        assertThat(stored.getWalletCents()).isEqualTo(50000);
    }

    @Test
    void eventCrud() {
        EventService service = new EventService(events);
        Event published = event(1L, "Indie Rock Night", "music", "Shanghai", 0, 10, 1L, "PUBLISHED");
        Event other = event(2L, "Morning run", "sports", "Beijing", 0, 5, 1L, "PUBLISHED");
        when(events.findByStatusInOrderByStartsAtAsc(any())).thenReturn(List.of(published, other));
        assertThat(service.list("Shanghai", "music", "Indie")).hasSize(1);
        assertThat(service.list(null, null, "   ")).hasSize(2);
        assertThat(service.search("Shanghai", "music", "Indie", Instant.EPOCH, Instant.now().plusSeconds(40L * 86400),
                0, 99999, true, "price", true).getTotal()).isEqualTo(1);
        assertThat(service.search(null, null, null, null, null, null, null, false, "sold", true).getTotal()).isEqualTo(2);
        assertThat(service.search(null, null, null, null, null, null, null, null, "updatedAt", false).getTotal()).isEqualTo(2);
        assertThat(service.search(null, null, null, null, null, null, null, null, "startsAt", false, 0, 1).getRecords()).hasSize(1);
        assertThat(service.search(null, null, null, null, null, null, null, null, "startsAt", false, 9, 1).getRecords()).isEmpty();

        when(events.findById(1L)).thenReturn(Optional.of(published));
        assertThat(service.get(1L).remaining()).isEqualTo(10);
        when(events.findById(8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(8L)).isInstanceOf(BusinessException.class);

        assertThatThrownBy(service::mine).isInstanceOf(BusinessException.class);
        BaseContext.setUserId(1L);
        BaseContext.setRole("ORGANISER");
        when(events.findByOrganiserIdOrderByStartsAtDesc(1L)).thenReturn(List.of(published));
        assertThat(service.mine()).hasSize(1);

        EventRequest req = new EventRequest("New event", "desc", "art", "Shanghai", Instant.now(), 100, 20);
        when(events.save(any())).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(9L);
            return e;
        });
        assertThat(service.create(req).id()).isEqualTo(9L);

        published.setSold(3);
        assertThatThrownBy(() -> service.update(1L, new EventRequest("x", "", "art", "Shanghai", Instant.now(), 1, 1)))
                .isInstanceOf(BusinessException.class);
        assertThat(service.update(1L, req).title()).isEqualTo("New event");

        BaseContext.setUserId(2L);
        assertThatThrownBy(() -> service.update(1L, req)).isInstanceOf(BusinessException.class);
    }

    @Test
    void bookingAndKafka() {
        EventService eventService = new EventService(events);
        PopularCache popularCache = new PopularCache();
        BookingService service = new BookingService(bookings, eventService, events, ticketService, tickets, users, outbox, popularCache);
        when(events.incrementSold(any(), anyInt())).thenReturn(1);
        when(events.decrementSoldForCustomerCancellation(any(), anyInt())).thenReturn(1);
        when(users.debitWalletIfEnough(any(), anyLong())).thenReturn(1);
        when(users.creditWallet(any(), anyLong())).thenReturn(1);
        when(bookings.cancelConfirmed(any())).thenReturn(1, 0);
        when(ticketService.lockForBooking(any())).thenReturn(List.of());
        when(tickets.countByBookingIdAndStatus(any(), any())).thenReturn(0L);
        when(ticketService.issue(any(), any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(new CreateBookingRequest(1L, 1)))
                .isInstanceOf(BusinessException.class);

        BaseContext.setUserId(7L);
        BaseContext.setRole("USER");
        Event cancelled = event(1L, "Cancelled event", "music", "Shanghai", 0, 10, 1L, "CANCELLED");
        when(events.findById(1L)).thenReturn(Optional.of(cancelled));
        assertThatThrownBy(() -> service.create(new CreateBookingRequest(1L, 1)))
                .isInstanceOf(BusinessException.class);

        Event full = event(2L, "Sold-out event", "music", "Shanghai", 10, 10, 1L, "PUBLISHED");
        when(events.findById(2L)).thenReturn(Optional.of(full));
        assertThatThrownBy(() -> service.create(new CreateBookingRequest(2L, 1)))
                .isInstanceOf(BusinessException.class);

        Event open = event(3L, "Bookable", "music", "Shanghai", 1, 10, 1L, "PUBLISHED");
        when(events.findById(3L)).thenReturn(Optional.of(open));
        when(bookings.save(any())).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(11L);
            return b;
        });
        assertThat(service.create(new CreateBookingRequest(3L, 2)).status()).isEqualTo("CONFIRMED");
        ArgumentCaptor<Booking> createdBooking = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).save(createdBooking.capture());
        assertThat(createdBooking.getValue().getPaidCents()).isEqualTo(200L);
        verify(users).debitWalletIfEnough(7L, 200L);
        verify(outbox).write(anyString(), eq("BOOKING_CREATED"), anyString(), anyString(), any());

        Booking mine = booking(11L, 7L, 3L, 2, "CONFIRMED");
        when(bookings.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(mine));
        assertThat(service.listMine()).hasSize(1);
        when(bookings.findById(11L)).thenReturn(Optional.of(mine));
        assertThat(service.get(11L).eventTitle()).isEqualTo("Bookable");

        when(bookings.findById(12L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(12L)).isInstanceOf(BusinessException.class);
        Booking other = booking(13L, 99L, 3L, 1, "CONFIRMED");
        when(bookings.findById(13L)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.get(13L)).isInstanceOf(BusinessException.class);

        assertThat(service.cancel(11L).status()).isEqualTo("CANCELLED");
        verify(events).decrementSoldForCustomerCancellation(3L, 2);
        verify(users).creditWallet(7L, 200L);
        assertThatThrownBy(() -> service.cancel(11L)).isInstanceOf(BusinessException.class);

        when(events.incrementSold(any(), anyInt())).thenReturn(0);
        Event stillOpen = event(3L, "Bookable", "music", "Shanghai", 9, 10, 1L, "PUBLISHED");
        when(events.findById(3L)).thenReturn(Optional.of(stillOpen));
        assertThatThrownBy(() -> service.create(new CreateBookingRequest(3L, 2)))
                .isInstanceOf(BusinessException.class);
        when(ticketService.forBooking(11L)).thenReturn(List.of());
        mine.setStatus("CONFIRMED");
        assertThat(service.tickets(11L)).isEmpty();
        service.toPublic(mine);
    }

    @Test
    void insufficientWalletDoesNotCreateOrderAfterInventoryReservation() {
        EventService eventService = new EventService(events);
        BookingService service = new BookingService(bookings, eventService, events, ticketService, tickets, users, outbox, new PopularCache());
        BaseContext.setUserId(7L);
        Event open = event(3L, "Bookable", "music", "Shanghai", 0, 10, 1L, "PUBLISHED");
        when(events.findById(3L)).thenReturn(Optional.of(open));
        when(events.incrementSold(3L, 2)).thenReturn(1);
        when(users.debitWalletIfEnough(7L, 200L)).thenReturn(0);

        assertThatThrownBy(() -> service.create(new CreateBookingRequest(3L, 2)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);

        verify(events).incrementSold(3L, 2);
        verify(bookings, never()).save(any());
        verify(ticketService, never()).issue(any(), any(), anyInt());
        verify(outbox, never()).write(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void checkedInTicketCannotBeCancelledOrRefunded() {
        EventService eventService = new EventService(events);
        BookingService service = new BookingService(bookings, eventService, events, ticketService, tickets, users, outbox, new PopularCache());
        BaseContext.setUserId(7L);
        Booking booking = booking(11L, 7L, 3L, 1, "CONFIRMED");
        Event event = event(3L, "Bookable", "music", "Shanghai", 1, 10, 1L, "PUBLISHED");
        Ticket checkedIn = new Ticket();
        checkedIn.setStatus(TicketStatus.CHECKED_IN);
        when(bookings.findById(11L)).thenReturn(Optional.of(booking));
        when(events.findById(3L)).thenReturn(Optional.of(event));
        when(events.decrementSoldForCustomerCancellation(3L, 1)).thenReturn(1);
        when(ticketService.lockForBooking(11L)).thenReturn(List.of(checkedIn));

        assertThatThrownBy(() -> service.cancel(11L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);

        verify(bookings, never()).cancelConfirmed(11L);
        verify(users, never()).creditWallet(7L, 100L);
        verify(ticketService, never()).cancelLocked(any());
    }

    @Test
    void customerCancellationRequiresAnUpcomingPublishedEvent() {
        EventService eventService = new EventService(events);
        BookingService service = new BookingService(bookings, eventService, events, ticketService, tickets, users, outbox, new PopularCache());
        BaseContext.setUserId(7L);
        Booking booking = booking(11L, 7L, 3L, 1, "CONFIRMED");
        Event event = event(3L, "Started event", "music", "Shanghai", 1, 10, 1L, "ONGOING");
        when(bookings.findById(11L)).thenReturn(Optional.of(booking));
        when(events.findById(3L)).thenReturn(Optional.of(event));
        when(events.decrementSoldForCustomerCancellation(3L, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(11L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);

        verify(ticketService, never()).lockForBooking(any());
        verify(bookings, never()).cancelConfirmed(11L);
        verify(users, never()).creditWallet(anyLong(), anyLong());
    }

    @Test
    void consumerAndHandlerInterceptor() throws Exception {
        InteractionService interactionService = new InteractionService(interactionRepo, metricsRepo);
        dev.kaiwen.eventpulse.sse.SseReminderPublisher reminders = org.mockito.Mockito.mock(dev.kaiwen.eventpulse.sse.SseReminderPublisher.class);
        BookingConsumer consumer = new BookingConsumer(consumedEvents, notifications, interactionService, reminders, new ObjectMapper());
        // 首次处理：写入 consumed_events 成功，创建通知，并写 BOOK interaction（张数 4）。
        when(consumedEvents.tryInsert(eq("eventpulse"), anyString())).thenReturn(1);
        consumer.onMessage("{\"type\":\"BOOKING_CREATED\",\"bookingId\":5,\"dedupKey\":\"k\",\"userId\":1,\"eventId\":2,\"quantity\":4}");
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(captor.capture());
        assertThat(captor.getValue().getBookingId()).isEqualTo(5L);
        verify(interactionRepo).save(org.mockito.ArgumentMatchers.argThat(i -> "BOOK".equals(((Interaction) i).getType())));
        verify(metricsRepo).incrementBookings(2L, java.time.LocalDate.now(), 4);
        // 重复投递：consumed_events 插入 0 行，直接结束，不重复通知。
        when(consumedEvents.tryInsert(eq("eventpulse"), anyString())).thenReturn(0);
        consumer.onMessage("{\"type\":\"BOOKING_CREATED\",\"bookingId\":5,\"dedupKey\":\"k\"}");
        verify(notifications, org.mockito.Mockito.times(1)).save(any());
        // EVENT_CANCELLED 只创建通知，不写 interaction。
        when(consumedEvents.tryInsert(eq("eventpulse"), anyString())).thenReturn(1);
        consumer.onMessage("{\"type\":\"EVENT_CANCELLED\",\"bookingId\":6,\"dedupKey\":\"e\",\"userId\":1,\"eventId\":2}");
        verify(notifications, org.mockito.Mockito.times(2)).save(any(Notification.class));
        // 无法解析的消息不再被静默吞掉。
        assertThatThrownBy(() -> consumer.onMessage("not-json")).isInstanceOf(Exception.class);

        AppProperties props = new AppProperties();
        props.setSecretKey("test-secret-key-change-me-0123456789ab");
        JwtInterceptor interceptor = new JwtInterceptor(new JwtService(props));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/events");
        assertThat(JwtInterceptor.isPublic(req)).isTrue();
        req.setRequestURI("/api/events/3");
        assertThat(JwtInterceptor.isPublic(req)).isTrue();
        req.setMethod("POST");
        req.setRequestURI("/api/auth/login");
        assertThat(JwtInterceptor.isPublic(req)).isTrue();
        req.setMethod("OPTIONS");
        assertThat(JwtInterceptor.isPublic(req)).isTrue();
        MockHttpServletRequest authed = new MockHttpServletRequest("GET", "/api/bookings");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(authed, res, new Object())).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        authed.addHeader("Authorization", "Bearer " + new JwtService(props).createToken(1L, "USER"));
        assertThat(interceptor.preHandle(authed, new MockHttpServletResponse(), new Object())).isTrue();
        interceptor.afterCompletion(authed, res, new Object(), null);
        MockHttpServletRequest queryTok = new MockHttpServletRequest("GET", "/api/bookings/1/events");
        queryTok.setParameter("access_token", new JwtService(props).createToken(1L, "USER"));
        // 长期 JWT 不再放进 URL（代理日志/浏览器记录会泄漏）：query 参数必须被拒绝。
        assertThat(interceptor.preHandle(queryTok, new MockHttpServletResponse(), new Object())).isFalse();
        MockHttpServletRequest bad = new MockHttpServletRequest("GET", "/api/bookings");
        bad.addHeader("Authorization", "Bearer bad");
        assertThat(interceptor.preHandle(bad, new MockHttpServletResponse(), new Object())).isFalse();

        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        assertThat(handler.handleBusiness(new BusinessException("x")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        MethodArgumentNotValidException invalid = new MethodArgumentNotValidException(
                null, new BeanPropertyBindingResult(new Object(), "req"));
        invalid.getBindingResult().addError(new FieldError("req", "email", "不能为空"));
        assertThat(handler.handleValid(invalid).getBody().getMsg()).contains("email");
        BeanPropertyBindingResult empty = new BeanPropertyBindingResult(new Object(), "req");
        assertThat(handler.handleValid(new MethodArgumentNotValidException(null, empty)).getBody().getMsg())
                .isEqualTo("Invalid request");
        assertThat(handler.handleOther(new RuntimeException()).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(handler.handleOther(new RuntimeException("boom")).getBody().getMsg()).isEqualTo("boom");
    }

    @Test
    void entityAccessors() {
        User user = new User();
        user.setId(1L);
        user.setEmail("a@b.c");
        user.setPassword("p");
        user.setName("n");
        user.setRole("USER");
        assertThat(user.getName()).isEqualTo("n");

        Event event = new Event();
        event.setId(1L);
        event.setTitle("t");
        event.setDescription("d");
        event.setCategory("c");
        event.setCity("Shanghai");
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        event.setStartsAt(now);
        event.setPriceCents(100);
        event.setCapacity(5);
        event.setSold(1);
        event.setOrganiserId(2L);
        event.setStatus("PUBLISHED");
        event.setCreatedAt(now);
        assertThat(event.getStartsAt()).isEqualTo(now);
        assertThat(event.remaining()).isEqualTo(4);

        Booking booking = new Booking();
        booking.setId(1L);
        booking.setUserId(2L);
        booking.setEventId(3L);
        booking.setQuantity(1);
        booking.setStatus("CONFIRMED");
        booking.setCreatedAt(now);
        assertThat(booking.getCreatedAt()).isEqualTo(now);

        Notification n = new Notification();
        n.setId(1L);
        n.setBookingId(2L);
        n.setMessage("m");
        n.setCreatedAt(now);
        assertThat(n.getMessage()).isEqualTo("m");
        assertThat(new Notification(9L, "hi").getBookingId()).isEqualTo(9L);
    }

    @Test
    void controllersAndConfig() {
        AuthService auth = new AuthService(users, passwordEncoder, new JwtService(new AppProperties()),
                bookings, tickets, favourites, notifications);
        when(users.existsByEmail("n@b.c")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(users.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        AuthController authController = new AuthController(auth);
        assertThat(authController.register(new RegisterRequest("n@b.c", "123456", "N")).getCode()).isEqualTo(1);
        User stored = user(1L, "n@b.c", "hash", "USER");
        when(users.findByEmail("n@b.c")).thenReturn(Optional.of(stored));
        when(passwordEncoder.matches("123456", "hash")).thenReturn(true);
        assertThat(authController.login(new LoginRequest("n@b.c", "123456")).getData().token()).isNotBlank();
        BaseContext.setUserId(1L);
        when(users.findById(1L)).thenReturn(Optional.of(stored));
        assertThat(authController.me().getData().email()).isEqualTo("n@b.c");

        EventService eventService = new EventService(events);
        EventController eventsApi = new EventController(eventService);
        when(events.findByStatusInOrderByStartsAtAsc(any())).thenReturn(List.of());
        assertThat(eventsApi.list(null, null, null, null, null, null, null, null, null, null, null, null).getData()).isEmpty();
        Event published = event(1L, "t", "music", "Shanghai", 0, 10, 1L, "PUBLISHED");
        when(events.findById(1L)).thenReturn(Optional.of(published));
        assertThat(eventsApi.get(1L).getData().id()).isEqualTo(1L);
        BaseContext.setRole("ORGANISER");
        BaseContext.setUserId(1L);
        when(events.findByOrganiserIdOrderByStartsAtDesc(1L)).thenReturn(List.of(published));
        assertThat(eventsApi.mine().getData()).hasSize(1);
        EventRequest req = new EventRequest("New", "d", "art", "Shanghai", Instant.now().plusSeconds(7L * 86400), 1, 10);
        when(events.save(any())).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(2L);
            return e;
        });
        assertThat(eventsApi.create(req).getData().id()).isEqualTo(2L);
        assertThat(eventsApi.update(1L, req).getCode()).isEqualTo(1);

        when(events.incrementSold(any(), anyInt())).thenReturn(1);
        when(events.decrementSoldForCustomerCancellation(any(), anyInt())).thenReturn(1);
        when(users.debitWalletIfEnough(any(), anyLong())).thenReturn(1);
        when(users.creditWallet(any(), anyLong())).thenReturn(1);
        when(bookings.cancelConfirmed(any())).thenReturn(1);
        when(ticketService.lockForBooking(any())).thenReturn(List.of());
        when(tickets.countByBookingIdAndStatus(any(), any())).thenReturn(0L);
        when(ticketService.issue(any(), any(), anyInt())).thenReturn(List.of());
        BookingService bookingService = new BookingService(bookings, eventService, events, ticketService, tickets, users, outbox, new PopularCache());
        BookingController bookingsApi = new BookingController(bookingService);
        when(bookings.save(any())).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(4L);
            return b;
        });
        published.setStatus("PUBLISHED");
        published.setSold(0);
        assertThat(bookingsApi.create(new CreateBookingRequest(1L, 1)).getData().id()).isEqualTo(4L);
        Booking mine = booking(4L, 1L, 1L, 1, "CONFIRMED");
        when(bookings.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(mine));
        when(bookings.findById(4L)).thenReturn(Optional.of(mine));
        assertThat(bookingsApi.list().getData()).hasSize(1);
        assertThat(bookingsApi.get(4L).getData().id()).isEqualTo(4L);
        assertThat(bookingsApi.cancel(4L).getData().status()).isEqualTo("CANCELLED");
        when(ticketService.forBooking(4L)).thenReturn(List.of());
        mine.setStatus("CONFIRMED");
        assertThat(bookingsApi.tickets(4L)).isNotNull();

        NotificationController notes = new NotificationController(platform);
        when(platform.myNotifications()).thenReturn(List.of());
        assertThat(notes.list().getData()).isEmpty();
        var note = new dev.kaiwen.eventpulse.dto.BookingDtos.NotificationVo(
                1L, 1L, 1L, 4L, "BOOKING", "Booking confirmed", "Processed: BOOKING_CREATED", null, null,
                Instant.parse("2026-08-31T00:00:00Z"));
        when(platform.myNotifications()).thenReturn(List.of(note));
        assertThat(notes.list().getData()).hasSize(1);
        notes.read(1L);

        AppProperties props = new AppProperties();
        WebMvcConfig web = new WebMvcConfig(new JwtInterceptor(new JwtService(props)),
                new RequestLoggingInterceptor(),
                new dev.kaiwen.eventpulse.interceptor.InternalServiceInterceptor(props, new JwtService(props)), props);
        web.addInterceptors(new org.springframework.web.servlet.config.annotation.InterceptorRegistry());
        web.addCorsMappings(new org.springframework.web.servlet.config.annotation.CorsRegistry());
        assertThat(new OpenApiConfig().openAPI().getInfo().getTitle()).contains("EventPulse");
        assertThat(new PasswordConfig().passwordEncoder().encode("x")).isNotBlank();
        new EventPulseApplication();
    }

    private static User user(Long id, String email, String password, String role) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setPassword(password);
        user.setName("n");
        user.setRole(role);
        return user;
    }

    private static Event event(Long id, String title, String category, String city, int sold, int capacity,
            Long organiserId, String status) {
        Event event = new Event();
        event.setId(id);
        event.setTitle(title);
        event.setDescription("d");
        event.setCategory(category);
        event.setCity(city);
        Instant starts = Instant.now().plusSeconds(14L * 24 * 3600);
        event.setStartsAt(starts);
        event.setEndsAt(starts.plusSeconds(3 * 3600));
        event.setPriceCents(100);
        event.setCapacity(capacity);
        event.setSold(sold);
        event.setMaxQuantityPerBooking(10);
        event.setOrganiserId(organiserId);
        event.setStatus(status);
        event.setCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        event.setUpdatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        return event;
    }

    private static Booking booking(Long id, Long userId, Long eventId, int quantity, String status) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setUserId(userId);
        booking.setEventId(eventId);
        booking.setQuantity(quantity);
        booking.setPaidCents((long) quantity * 100);
        booking.setStatus(status);
        booking.setCreatedAt(Instant.parse("2026-08-31T00:00:00Z"));
        return booking;
    }
}
