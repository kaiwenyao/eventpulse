package dev.kaiwen.eventpulse.seed;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;
import dev.kaiwen.eventpulse.seed.DemoCatalog.EventSpec;
import dev.kaiwen.eventpulse.seed.DemoCatalog.UserSpec;

/**
 * demo profile 启动时播种一整套可以直接点的演示数据：账号、活动、订单、电子票、
 * 收藏、行为流水、每日统计和站内消息。
 *
 * 只在数据库还没有主演示账号时执行一次；`make down-v` 删掉数据卷后会重新播种。
 * 这里刻意不走 BookingService，因为播种既不需要 Kafka，也不需要登录态。
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements CommandLineRunner {

    /** 售票窗口：活动开始前 45 天开售，开演前 2 小时截止。 */
    private static final int SALES_WINDOW_DAYS = 45;
    private static final int SALES_CUTOFF_HOURS = 2;

    private final UserRepository users;
    private final EventRepository events;
    private final PasswordEncoder passwordEncoder;
    private final DemoEngagementSeeder engagement;

    public DemoDataSeeder(
            UserRepository users,
            EventRepository events,
            PasswordEncoder passwordEncoder,
            DemoEngagementSeeder engagement) {
        this.users = users;
        this.events = events;
        this.passwordEncoder = passwordEncoder;
        this.engagement = engagement;
    }

    @Override
    public void run(String... args) {
        if (users.existsByEmail(DemoCatalog.USERS.get(0).email())) {
            return;
        }
        Instant now = Instant.now();
        Map<String, User> byEmail = seedUsers();
        List<Event> seeded = seedEvents(byEmail, now);
        engagement.seed(byEmail, seeded, now);
    }

    /** 建账号。余额先扣掉演示订单里已确认的金额，个人中心的「已消费」才对得上。 */
    private Map<String, User> seedUsers() {
        Map<String, User> byEmail = new LinkedHashMap<>();
        for (UserSpec spec : DemoCatalog.USERS) {
            User user = new User();
            user.setEmail(spec.email());
            user.setPassword(passwordEncoder.encode(spec.rawPassword()));
            user.setName(spec.name());
            user.setRole(spec.role());
            user.setWalletCents(Math.max(0, spec.walletCents() - DemoCatalog.spentCents(spec.email())));
            byEmail.put(spec.email(), users.save(user));
        }
        return byEmail;
    }

    /** 建活动，返回顺序与 {@link DemoCatalog#EVENTS} 一致，供订单按下标引用。 */
    private List<Event> seedEvents(Map<String, User> byEmail, Instant now) {
        List<Event> seeded = new ArrayList<>();
        for (int i = 0; i < DemoCatalog.EVENTS.size(); i++) {
            seeded.add(events.save(toEvent(DemoCatalog.EVENTS.get(i), DemoCatalog.soldFor(i), byEmail, now)));
        }
        return seeded;
    }

    private static Event toEvent(EventSpec spec, int sold, Map<String, User> byEmail, Instant now) {
        Instant startsAt = now.plus(spec.startOffsetHours(), ChronoUnit.HOURS);
        Event event = new Event();
        event.setTitle(spec.title());
        event.setSummary(spec.summary());
        event.setDescription(spec.description());
        event.setCategory(spec.category());
        event.setCity(spec.venue().city());
        event.setVenueName(spec.venue().name());
        event.setAddress(spec.venue().address());
        event.setLatitude(spec.venue().latitude());
        event.setLongitude(spec.venue().longitude());
        event.setStartsAt(startsAt);
        event.setEndsAt(startsAt.plus(spec.durationHours(), ChronoUnit.HOURS));
        event.setSalesStartAt(startsAt.minus(SALES_WINDOW_DAYS, ChronoUnit.DAYS));
        event.setSalesEndAt(startsAt.minus(SALES_CUTOFF_HOURS, ChronoUnit.HOURS));
        event.setMaxQuantityPerBooking(spec.priceCents() == 0 ? 4 : 10);
        event.setContactInfo(DemoCatalog.ORGANISER_CONTACTS.get(spec.organiserIndex()));
        event.setAttendanceNotes(DemoCatalog.attendanceNotes(spec.category()));
        event.setPriceCents(spec.priceCents());
        event.setCapacity(spec.capacity());
        event.setSold(sold);
        event.setOrganiserId(organiserId(spec, byEmail));
        event.setStatus(spec.status());
        applyTerminalState(event, spec, now);
        event.setCreatedAt(event.getSalesStartAt());
        event.setUpdatedAt(now);
        return event;
    }

    /** 已取消 / 已归档的活动需要补上原因与时间戳，否则前端的状态卡片是空的。 */
    private static void applyTerminalState(Event event, EventSpec spec, Instant now) {
        if (EventStatus.CANCELLED.equals(spec.status())) {
            event.setCancellationReason(spec.cancellationReason());
            event.setCancelledAt(now.minus(2, ChronoUnit.DAYS));
        }
        if (EventStatus.ARCHIVED.equals(spec.status())) {
            event.setArchiveNote("Past event, kept on file.");
            event.setArchivedAt(now.minus(7, ChronoUnit.DAYS));
        }
    }

    private static Long organiserId(EventSpec spec, Map<String, User> byEmail) {
        int userIndex = DemoCatalog.ORGANISER_INDEXES.get(spec.organiserIndex());
        return byEmail.get(DemoCatalog.USERS.get(userIndex).email()).getId();
    }
}
