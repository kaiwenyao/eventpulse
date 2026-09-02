package dev.kaiwen.eventpulse.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.domain.TicketStatus;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.EventDailyMetric;
import dev.kaiwen.eventpulse.entity.EventFavourite;
import dev.kaiwen.eventpulse.entity.Interaction;
import dev.kaiwen.eventpulse.entity.Notification;
import dev.kaiwen.eventpulse.entity.Ticket;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.entity.UserPreference;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.EventDailyMetricRepository;
import dev.kaiwen.eventpulse.repository.EventFavouriteRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.InteractionRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;
import dev.kaiwen.eventpulse.repository.TicketRepository;
import dev.kaiwen.eventpulse.repository.UserPreferenceRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;
import dev.kaiwen.eventpulse.seed.DemoCatalog.BookingSpec;
import dev.kaiwen.eventpulse.seed.DemoCatalog.EventSpec;
import dev.kaiwen.eventpulse.seed.DemoCatalog.UserSpec;
import dev.kaiwen.eventpulse.service.TicketService;

/**
 * demo 播种的单元测试。仓库全部是 mock，断言落在「播下去的那批实体长什么样」上：
 * 库存与订单对得上、钱包扣得动、票据状态正确、统计可复现。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DemoSeedTest {

    @Mock
    UserRepository users;
    @Mock
    EventRepository events;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    BookingRepository bookings;
    @Mock
    TicketService ticketService;
    @Mock
    TicketRepository tickets;
    @Mock
    EventFavouriteRepository favourites;
    @Mock
    InteractionRepository interactions;
    @Mock
    EventDailyMetricRepository metrics;
    @Mock
    NotificationRepository notifications;
    @Mock
    UserPreferenceRepository preferences;

    private DemoDataSeeder seeder;

    @BeforeEach
    void setUp() {
        AtomicLong userIds = new AtomicLong();
        AtomicLong eventIds = new AtomicLong();
        AtomicLong bookingIds = new AtomicLong();
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(users.save(any())).thenAnswer(call -> withId(call.getArgument(0), userIds.incrementAndGet()));
        when(events.save(any())).thenAnswer(call -> withId(call.getArgument(0), eventIds.incrementAndGet()));
        when(bookings.save(any())).thenAnswer(call -> withId(call.getArgument(0), bookingIds.incrementAndGet()));
        when(ticketService.issue(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> issue(call.getArgument(0), call.getArgument(1), call.getArgument(2)));

        DemoEngagementSeeder engagement = new DemoEngagementSeeder(
                bookings, ticketService, tickets, favourites, interactions, metrics, notifications, preferences);
        seeder = new DemoDataSeeder(users, events, passwordEncoder, engagement);
    }

    @Test
    void skipsSeedingWhenThePrimaryDemoAccountAlreadyExists() {
        // Arrange
        when(users.existsByEmail("user@eventpulse.dev")).thenReturn(true);

        // Act
        seeder.seed();

        // Assert
        verify(users, never()).save(any());
        verify(events, never()).save(any());
        verify(bookings, never()).save(any());
    }

    @Test
    void seedsEveryCatalogueAccountWithItsRoleAndHashedPassword() {
        // Act
        seeder.seed();

        // Assert
        List<User> saved = captureUsers();
        assertThat(saved).hasSize(DemoCatalog.USERS.size());
        assertThat(saved).extracting(User::getEmail)
                .containsExactlyElementsOf(DemoCatalog.USERS.stream().map(UserSpec::email).toList());
        assertThat(saved).allSatisfy(user -> assertThat(user.getPassword()).isEqualTo("hash"));
        assertThat(saved).extracting(User::getRole).containsOnly("USER", "ORGANISER");
        assertThat(saved).filteredOn(user -> "ORGANISER".equals(user.getRole())).hasSize(3);
    }

    @Test
    void walletBalancesAreTheStartingAmountMinusEveryConfirmedBooking() {
        // Act
        seeder.seed();

        // Assert
        Map<String, Long> balances = captureUsers().stream()
                .collect(Collectors.toMap(User::getEmail, User::getWalletCents));
        for (UserSpec spec : DemoCatalog.USERS) {
            long spent = DemoCatalog.spentCents(spec.email());
            assertThat(balances.get(spec.email()))
                    .as("余额 %s", spec.email())
                    .isEqualTo(spec.walletCents() - spent)
                    .isNotNegative();
        }
        // 主演示账号确实花过钱，否则个人中心的「已消费」是 0，这个断言就白写了。
        assertThat(DemoCatalog.spentCents("user@eventpulse.dev")).isPositive();
    }

    @Test
    void everyCatalogueEventIsSeededWithVenueSalesWindowAndOrganiser() {
        // Act
        seeder.seed();

        // Assert
        List<Event> saved = captureEvents();
        assertThat(saved).hasSize(DemoCatalog.EVENTS.size());
        assertThat(saved).allSatisfy(event -> {
            assertThat(event.getTitle()).isNotBlank();
            assertThat(event.getSummary()).isNotBlank();
            assertThat(event.getDescription()).isNotBlank();
            assertThat(event.getVenueName()).isNotBlank();
            assertThat(event.getAddress()).isNotBlank();
            assertThat(event.getLatitude()).isNotNull();
            assertThat(event.getLongitude()).isNotNull();
            assertThat(event.getContactInfo()).isNotBlank();
            assertThat(event.getAttendanceNotes()).isNotBlank();
            assertThat(event.getOrganiserId()).isNotNull();
            assertThat(event.getEndsAt()).isAfter(event.getStartsAt());
            assertThat(event.getSalesStartAt()).isBefore(event.getSalesEndAt());
            assertThat(event.getSalesEndAt()).isBefore(event.getStartsAt());
        });
        assertThat(saved).extracting(Event::getCategory).containsOnly("music", "tech", "sports", "art");
        assertThat(saved).extracting(Event::getCity).contains("Shanghai", "Beijing", "Hangzhou", "Shenzhen", "Chengdu", "Guangzhou");
        assertThat(saved).extracting(Event::getStatus).contains(
                EventStatus.DRAFT, EventStatus.PUBLISHED, EventStatus.ONGOING,
                EventStatus.FINISHED, EventStatus.CANCELLED, EventStatus.ARCHIVED);
        assertThat(saved).extracting(Event::getOrganiserId).doesNotContainNull()
                .containsAll(captureUsers().stream()
                        .filter(user -> "ORGANISER".equals(user.getRole()))
                        .map(User::getId)
                        .toList());
    }

    @Test
    void soldNeverExceedsCapacityAndAbsorbsTheConfirmedDemoBookings() {
        // Act
        seeder.seed();

        // Assert
        List<Event> saved = captureEvents();
        for (int i = 0; i < saved.size(); i++) {
            int index = i;
            EventSpec spec = DemoCatalog.EVENTS.get(index);
            Event event = saved.get(index);
            int confirmed = DemoCatalog.BOOKINGS.stream()
                    .filter(booking -> booking.eventIndex() == index && "CONFIRMED".equals(booking.status()))
                    .mapToInt(BookingSpec::quantity)
                    .sum();
            assertThat(event.getSold()).as(spec.title()).isEqualTo(spec.baseSold() + confirmed);
            assertThat(event.getSold()).as(spec.title()).isLessThanOrEqualTo(event.getCapacity());
        }
    }

    @Test
    void statusTimestampsMatchTheLifecycleTheEventClaims() {
        // Act
        seeder.seed();

        // Assert
        List<Event> saved = captureEvents();
        Instant now = Instant.now();
        for (Event event : saved) {
            switch (event.getStatus()) {
                case EventStatus.ONGOING -> assertThat(event.getStartsAt()).isBefore(now);
                case EventStatus.FINISHED -> assertThat(event.getEndsAt()).isBefore(now);
                case EventStatus.PUBLISHED -> assertThat(event.getStartsAt()).isAfter(now);
                case EventStatus.CANCELLED -> {
                    assertThat(event.getCancellationReason()).isNotBlank();
                    assertThat(event.getCancelledAt()).isNotNull();
                }
                case EventStatus.ARCHIVED -> {
                    assertThat(event.getArchiveNote()).isNotBlank();
                    assertThat(event.getArchivedAt()).isNotNull();
                }
                default -> assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
            }
        }
        assertThat(saved).filteredOn(event -> EventStatus.ONGOING.equals(event.getStatus()))
                .allSatisfy(event -> assertThat(event.getEndsAt()).isAfter(now));
    }

    @Test
    void bookingsCarryAPriceSnapshotAndCancelledOnesGetACancelTimestamp() {
        // Act
        seeder.seed();

        // Assert
        List<Booking> saved = captureBookings();
        assertThat(saved).hasSize(DemoCatalog.BOOKINGS.size());
        List<Event> seededEvents = captureEvents();
        for (int i = 0; i < saved.size(); i++) {
            BookingSpec spec = DemoCatalog.BOOKINGS.get(i);
            Booking booking = saved.get(i);
            Event event = seededEvents.get(spec.eventIndex());
            assertThat(booking.getQuantity()).isEqualTo(spec.quantity());
            assertThat(booking.getPaidCents()).isEqualTo((long) event.getPriceCents() * spec.quantity());
            assertThat(booking.getCreatedAt()).isBefore(Instant.now());
            if ("CANCELLED".equals(spec.status())) {
                assertThat(booking.getCancelledAt()).isNotNull();
            }
            else {
                assertThat(booking.getCancelledAt()).isNull();
            }
        }
        assertThat(saved).extracting(Booking::getStatus).contains("CONFIRMED", "CANCELLED");
    }

    @Test
    void ticketsMirrorTheirBookingAndTheSeededCheckIns() {
        // Act
        seeder.seed();

        // Assert
        List<Ticket> saved = captureTickets();
        int expected = DemoCatalog.BOOKINGS.stream().mapToInt(BookingSpec::quantity).sum();
        int checkedIn = DemoCatalog.BOOKINGS.stream().mapToInt(BookingSpec::checkedIn).sum();
        int cancelled = DemoCatalog.BOOKINGS.stream()
                .filter(booking -> "CANCELLED".equals(booking.status()))
                .mapToInt(BookingSpec::quantity)
                .sum();
        assertThat(saved).hasSize(expected);
        assertThat(saved).filteredOn(ticket -> TicketStatus.CHECKED_IN.equals(ticket.getStatus())).hasSize(checkedIn);
        assertThat(saved).filteredOn(ticket -> TicketStatus.CANCELLED.equals(ticket.getStatus())).hasSize(cancelled);
        assertThat(saved).filteredOn(ticket -> TicketStatus.CHECKED_IN.equals(ticket.getStatus()))
                .allSatisfy(ticket -> {
                    assertThat(ticket.getCheckedInAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
                    assertThat(ticket.getCheckedInBy()).isNotNull();
                    assertThat(ticket.getCheckInSource()).isEqualTo("seed");
                });
        assertThat(checkedIn).isPositive();
        assertThat(cancelled).isPositive();
    }

    @Test
    void notificationsAreUniquePerBookingAndOldOnesArriveAlreadyRead() {
        // Act
        seeder.seed();

        // Assert
        List<Notification> saved = captureNotifications();
        assertThat(saved).hasSize(DemoCatalog.BOOKINGS.size());
        assertThat(saved).extracting(Notification::getDedupKey).doesNotHaveDuplicates();
        assertThat(saved).allSatisfy(notification -> {
            assertThat(notification.getUserId()).isNotNull();
            assertThat(notification.getBookingId()).isNotNull();
            assertThat(notification.getMessage()).isNotBlank();
            assertThat(notification.getTitle()).isNotBlank();
        });
        assertThat(saved).extracting(Notification::getType)
                .contains("BOOKING_CREATED", "BOOKING_CANCELLED");
        assertThat(saved).filteredOn(notification -> notification.getReadAt() != null).isNotEmpty();
        assertThat(saved).filteredOn(notification -> notification.getReadAt() == null).isNotEmpty();
    }

    @Test
    void favouritesAndPreferencesGiveRecommendationsSomethingToRankOn() {
        // Act
        seeder.seed();

        // Assert
        ArgumentCaptor<EventFavourite> favouriteCaptor = ArgumentCaptor.forClass(EventFavourite.class);
        verify(favourites, org.mockito.Mockito.times(DemoCatalog.FAVOURITES.size())).save(favouriteCaptor.capture());
        assertThat(favouriteCaptor.getAllValues()).allSatisfy(favourite -> {
            assertThat(favourite.getUserId()).isNotNull();
            assertThat(favourite.getEventId()).isNotNull();
            assertThat(favourite.getCreatedAt()).isBefore(Instant.now());
        });

        ArgumentCaptor<UserPreference> preferenceCaptor = ArgumentCaptor.forClass(UserPreference.class);
        verify(preferences, org.mockito.Mockito.times(DemoCatalog.PREFERENCES.size()))
                .save(preferenceCaptor.capture());
        assertThat(preferenceCaptor.getAllValues()).allSatisfy(preference -> {
            assertThat(preference.getCategories()).isNotBlank();
            assertThat(preference.getCities()).isNotBlank();
            assertThat(preference.getRadiusKm()).isPositive();
        });

        ArgumentCaptor<Interaction> interactionCaptor = ArgumentCaptor.forClass(Interaction.class);
        verify(interactions, org.mockito.Mockito.atLeastOnce()).save(interactionCaptor.capture());
        assertThat(interactionCaptor.getAllValues()).extracting(Interaction::getType)
                .contains("BOOK", "CANCEL", "SAVE", "VIEW", "CLICK");
    }

    @Test
    void dailyMetricsCoverOnlyPublicEventsAndFormAFunnel() {
        // Act
        seeder.seed();

        // Assert
        List<Event> seededEvents = captureEvents();
        long publicEvents = seededEvents.stream()
                .filter(event -> EventStatus.PUBLIC_LIST.contains(event.getStatus()))
                .count();
        List<EventDailyMetric> saved = captureMetrics();
        assertThat(saved).hasSize((int) publicEvents * 14);
        assertThat(saved).allSatisfy(metric -> {
            assertThat(metric.getViews()).isGreaterThanOrEqualTo(metric.getClicks());
            assertThat(metric.getClicks()).isGreaterThanOrEqualTo(metric.getSaves());
            assertThat(metric.getSaves()).isGreaterThanOrEqualTo(metric.getBookings());
            assertThat(metric.getCancels()).isNotNegative();
            assertThat(metric.getCheckIns()).isNotNegative();
        });

        // 未结束的活动，统计窗口停在今天，主办方「数据」页默认 14 天就能看到曲线。
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        assertThat(saved).extracting(EventDailyMetric::getMetricDate).contains(today);
    }

    @Test
    void seedingTwiceProducesTheSameNumbers() {
        // Arrange
        seeder.seed();
        List<String> first = captureMetrics().stream().map(DemoSeedTest::fingerprint).toList();

        // Act
        org.mockito.Mockito.reset(users, events, bookings, tickets, favourites,
                interactions, metrics, notifications, preferences, ticketService, passwordEncoder);
        setUp();
        seeder.seed();
        List<String> second = captureMetrics().stream().map(DemoSeedTest::fingerprint).toList();

        // Assert
        assertThat(second).isEqualTo(first);
    }

    private static String fingerprint(EventDailyMetric metric) {
        return metric.getEventId() + "/" + metric.getViews() + "/" + metric.getClicks() + "/"
                + metric.getSaves() + "/" + metric.getBookings() + "/" + metric.getTickets() + "/"
                + metric.getUnsaves() + "/" + metric.getCancels() + "/" + metric.getCheckIns();
    }

    private List<User> captureUsers() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private List<Event> captureEvents() {
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private List<Booking> captureBookings() {
        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookings, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private List<Ticket> captureTickets() {
        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(tickets, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private List<Notification> captureNotifications() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private List<EventDailyMetric> captureMetrics() {
        ArgumentCaptor<EventDailyMetric> captor = ArgumentCaptor.forClass(EventDailyMetric.class);
        verify(metrics, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    /** 仓库 mock 里模拟数据库分配自增主键。 */
    private static Object withId(Object entity, long id) {
        if (entity instanceof User user) {
            user.setId(id);
        }
        else if (entity instanceof Event event) {
            event.setId(id);
        }
        else if (entity instanceof Booking booking) {
            booking.setId(id);
        }
        return entity;
    }

    /** TicketService.issue 的替身：只造出对象，不加密也不落库。 */
    private static List<Ticket> issue(Long bookingId, Long eventId, int quantity) {
        List<Ticket> issued = new ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            Ticket ticket = new Ticket();
            ticket.setId(bookingId * 100 + i);
            ticket.setBookingId(bookingId);
            ticket.setEventId(eventId);
            ticket.setTicketCodeHash("hash-" + bookingId + "-" + i);
            ticket.setTicketCodeCipher("cipher-" + bookingId + "-" + i);
            ticket.setStatus(TicketStatus.VALID);
            ticket.setCreatedAt(Instant.now());
            issued.add(ticket);
        }
        return issued;
    }
}
