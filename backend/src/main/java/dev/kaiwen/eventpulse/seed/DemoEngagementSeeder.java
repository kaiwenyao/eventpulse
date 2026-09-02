package dev.kaiwen.eventpulse.seed;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

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
import dev.kaiwen.eventpulse.repository.InteractionRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;
import dev.kaiwen.eventpulse.repository.TicketRepository;
import dev.kaiwen.eventpulse.repository.UserPreferenceRepository;
import dev.kaiwen.eventpulse.seed.DemoCatalog.BookingSpec;
import dev.kaiwen.eventpulse.seed.DemoCatalog.FavouriteSpec;
import dev.kaiwen.eventpulse.seed.DemoCatalog.PreferenceSpec;
import dev.kaiwen.eventpulse.service.TicketService;

/**
 * 播种账号与活动之外的「有人用过」的痕迹：订单、电子票、收藏、行为流水、
 * 每日统计和站内消息。拆成单独的组件，是因为它要注入的仓库比 {@link DemoDataSeeder} 多得多。
 *
 * 统计数据用活动 ID 和日期算出来，不用随机数：同一份 demo 每次播种得到的曲线一致，
 * 截图和测试才不会漂。
 */
@Component
public class DemoEngagementSeeder {

    /** 每个活动生成多少天的每日统计，与主办方「数据」页默认的 14 天窗口对齐。 */
    private static final int METRIC_DAYS = 14;

    private final BookingRepository bookings;
    private final TicketService ticketService;
    private final TicketRepository tickets;
    private final EventFavouriteRepository favourites;
    private final InteractionRepository interactions;
    private final EventDailyMetricRepository metrics;
    private final NotificationRepository notifications;
    private final UserPreferenceRepository preferences;

    public DemoEngagementSeeder(
            BookingRepository bookings,
            TicketService ticketService,
            TicketRepository tickets,
            EventFavouriteRepository favourites,
            InteractionRepository interactions,
            EventDailyMetricRepository metrics,
            NotificationRepository notifications,
            UserPreferenceRepository preferences) {
        this.bookings = bookings;
        this.ticketService = ticketService;
        this.tickets = tickets;
        this.favourites = favourites;
        this.interactions = interactions;
        this.metrics = metrics;
        this.notifications = notifications;
        this.preferences = preferences;
    }

    /** seeded 的顺序必须与 {@link DemoCatalog#EVENTS} 一致，订单按下标引用活动。 */
    public void seed(Map<String, User> byEmail, List<Event> seeded, Instant now) {
        seedBookings(byEmail, seeded, now);
        seedFavourites(byEmail, seeded, now);
        seedPreferences(byEmail, now);
        seedMetrics(seeded, now);
    }

    private void seedBookings(Map<String, User> byEmail, List<Event> seeded, Instant now) {
        for (BookingSpec spec : DemoCatalog.BOOKINGS) {
            Event event = seeded.get(spec.eventIndex());
            User buyer = byEmail.get(spec.userEmail());
            Booking booking = saveBooking(spec, event, buyer, now);
            issueTickets(spec, booking, event, now);
            saveBookingNotification(spec, booking, event, buyer, now);
            record(buyer.getId(), event.getId(), "CONFIRMED".equals(spec.status()) ? "BOOK" : "CANCEL",
                    now.plus(spec.createdOffsetHours(), ChronoUnit.HOURS));
        }
    }

    private Booking saveBooking(BookingSpec spec, Event event, User buyer, Instant now) {
        Instant createdAt = now.plus(spec.createdOffsetHours(), ChronoUnit.HOURS);
        Booking booking = new Booking();
        booking.setUserId(buyer.getId());
        booking.setEventId(event.getId());
        booking.setQuantity(spec.quantity());
        booking.setPaidCents((long) event.getPriceCents() * spec.quantity());
        booking.setStatus(spec.status());
        booking.setCreatedAt(createdAt);
        if (!"CONFIRMED".equals(spec.status())) {
            booking.setCancelledAt(createdAt.plus(6, ChronoUnit.HOURS));
        }
        return bookings.save(booking);
    }

    /** 已取消的订单，票同步作废；已核销的票补上核销人和时间，主办方的参与者页才有内容。 */
    private void issueTickets(BookingSpec spec, Booking booking, Event event, Instant now) {
        List<Ticket> issued = ticketService.issue(booking.getId(), event.getId(), spec.quantity());
        for (int i = 0; i < issued.size(); i++) {
            Ticket ticket = issued.get(i);
            ticket.setCreatedAt(booking.getCreatedAt());
            if (!"CONFIRMED".equals(spec.status())) {
                ticket.setStatus(TicketStatus.CANCELLED);
            }
            else if (i < spec.checkedIn()) {
                ticket.setStatus(TicketStatus.CHECKED_IN);
                ticket.setCheckedInAt(checkInMoment(event, now));
                ticket.setCheckedInBy(event.getOrganiserId());
                ticket.setCheckInSource("seed");
            }
            tickets.save(ticket);
        }
    }

    /** 核销发生在活动开始后半小时；还没开始的活动就按「刚刚」算。 */
    private static Instant checkInMoment(Event event, Instant now) {
        Instant afterStart = event.getStartsAt().plus(30, ChronoUnit.MINUTES);
        return afterStart.isAfter(now) ? now : afterStart;
    }

    private void saveBookingNotification(BookingSpec spec, Booking booking, Event event, User buyer, Instant now) {
        boolean confirmed = "CONFIRMED".equals(spec.status());
        Notification notification = new Notification();
        notification.setUserId(buyer.getId());
        notification.setEventId(event.getId());
        notification.setBookingId(booking.getId());
        notification.setType(confirmed ? "BOOKING_CREATED" : "BOOKING_CANCELLED");
        notification.setTitle(confirmed ? "Booking confirmed" : "Booking cancelled");
        notification.setMessage(confirmed
                ? "You booked " + spec.quantity() + " ticket(s) for \"" + event.getTitle() + "\""
                : "You cancelled your booking for \"" + event.getTitle() + "\". The fare was returned to your wallet");
        notification.setDedupKey("SEED:" + notification.getType() + ":" + booking.getId());
        notification.setCreatedAt(booking.getCreatedAt());
        // 一周以前的消息当作已读，未读角标才不会挂着十几条历史消息。
        if (booking.getCreatedAt().isBefore(now.minus(7, ChronoUnit.DAYS))) {
            notification.setReadAt(booking.getCreatedAt().plus(1, ChronoUnit.HOURS));
        }
        notifications.save(notification);
    }

    /** 收藏同时补一条 SAVE 和一条 VIEW，推荐排序才有行为可用。 */
    private void seedFavourites(Map<String, User> byEmail, List<Event> seeded, Instant now) {
        for (int i = 0; i < DemoCatalog.FAVOURITES.size(); i++) {
            FavouriteSpec spec = DemoCatalog.FAVOURITES.get(i);
            Long userId = byEmail.get(spec.userEmail()).getId();
            Long eventId = seeded.get(spec.eventIndex()).getId();
            Instant at = now.minus(i + 1L, ChronoUnit.DAYS);
            EventFavourite favourite = new EventFavourite(userId, eventId);
            favourite.setCreatedAt(at);
            favourites.save(favourite);
            record(userId, eventId, "SAVE", at);
            record(userId, eventId, "VIEW", at.minus(10, ChronoUnit.MINUTES));
            record(userId, eventId, "CLICK", at.minus(9, ChronoUnit.MINUTES));
        }
    }

    private void seedPreferences(Map<String, User> byEmail, Instant now) {
        for (PreferenceSpec spec : DemoCatalog.PREFERENCES) {
            UserPreference preference = new UserPreference();
            preference.setUserId(byEmail.get(spec.userEmail()).getId());
            preference.setCategories(spec.categories());
            preference.setCities(spec.cities());
            preference.setLatitude(spec.latitude());
            preference.setLongitude(spec.longitude());
            preference.setRadiusKm(spec.radiusKm());
            preference.setUpdatedAt(now);
            preferences.save(preference);
        }
    }

    /**
     * 每个公开活动补 14 天的浏览 / 点击 / 收藏 / 下单漏斗。
     * 已结束的活动把窗口挪到结束当天，曲线才落在活动真正在售的那段时间里。
     */
    private void seedMetrics(List<Event> seeded, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneId.systemDefault());
        for (Event event : seeded) {
            if (!EventStatus.PUBLIC_LIST.contains(event.getStatus())) {
                continue;
            }
            LocalDate anchor = event.getEndsAt().isBefore(now)
                    ? LocalDate.ofInstant(event.getEndsAt(), ZoneId.systemDefault())
                    : today;
            for (int day = 0; day < METRIC_DAYS; day++) {
                metrics.save(dailyMetric(event.getId(), anchor.minusDays(day), day));
            }
        }
    }

    private static EventDailyMetric dailyMetric(Long eventId, LocalDate date, int day) {
        int views = 40 + spread(eventId, day, 1, 200);
        int clicks = Math.max(1, views / 3 - spread(eventId, day, 2, 10));
        int saves = Math.max(0, clicks / 4 - spread(eventId, day, 3, 3));
        int booked = Math.max(0, saves / 2 - spread(eventId, day, 4, 2));
        EventDailyMetric metric = new EventDailyMetric();
        metric.setEventId(eventId);
        metric.setMetricDate(date);
        metric.setViews(views);
        metric.setClicks(clicks);
        metric.setSaves(saves);
        metric.setUnsaves(spread(eventId, day, 5, 3));
        metric.setBookings(booked);
        metric.setTickets(booked + spread(eventId, day, 6, 3));
        metric.setCancels(spread(eventId, day, 7, 12) == 0 ? 1 : 0);
        metric.setCheckIns(spread(eventId, day, 8, 4) == 0 ? booked : 0);
        return metric;
    }

    /** 用活动 ID 和日期算出的伪随机值：曲线有起伏，但每次播种都一样。 */
    private static int spread(Long eventId, int day, int salt, int bound) {
        return Math.floorMod(eventId.intValue() * 31 + day * 17 + salt * 101, bound);
    }

    private void record(Long userId, Long eventId, String type, Instant at) {
        Interaction interaction = new Interaction();
        interaction.setUserId(userId);
        interaction.setEventId(eventId);
        interaction.setType(type);
        interaction.setCreatedAt(at);
        interactions.save(interaction);
    }
}
