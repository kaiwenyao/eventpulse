package dev.kaiwen.eventpulse.seed;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;

@Component
@Profile("demo")
public class DemoDataSeeder implements CommandLineRunner {

    private final UserRepository users;
    private final EventRepository events;
    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(UserRepository users, EventRepository events, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.events = events;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (users.existsByEmail("user@eventpulse.dev")) {
            return;
        }
        saveUser("user@eventpulse.dev", "User123456", "演示用户", "USER");
        User organiser = saveUser("organiser@eventpulse.dev", "Organiser123456", "演示主办方", "ORGANISER");
        saveEvent(organiser.getId(), "城市脉搏 · 独立摇滚之夜", "music", "上海", 18000, 300);
        saveEvent(organiser.getId(), "AI 与城市生活 · 技术沙龙", "tech", "上海", 4900, 120);
        saveEvent(organiser.getId(), "滨江晨跑 5K", "sports", "上海", 0, 200);
        saveEvent(organiser.getId(), "城市光影 · 数字艺术展", "art", "北京", 8800, 500);
    }

    private User saveUser(String email, String rawPassword, String name, String role) {
        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setName(name);
        user.setRole(role);
        user.setWalletCents(88800); // 演示钱包：预存 ¥888，方便个人中心直接展示与充值。
        return users.save(user);
    }

    private void saveEvent(Long organiserId, String title, String category, String city, int priceCents, int capacity) {
        Event event = new Event();
        event.setTitle(title);
        event.setDescription("演示活动，可直接预订。");
        event.setCategory(category);
        event.setCity(city);
        Instant starts = Instant.now().plus(14, ChronoUnit.DAYS);
        event.setStartsAt(starts);
        event.setEndsAt(starts.plus(3, ChronoUnit.HOURS));
        event.setPriceCents(priceCents);
        event.setCapacity(capacity);
        event.setSold(0);
        event.setMaxQuantityPerBooking(10);
        event.setOrganiserId(organiserId);
        event.setStatus("PUBLISHED");
        event.setCreatedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        events.save(event);
    }
}
