package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.domain.TicketStatus;
import dev.kaiwen.eventpulse.dto.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.Ticket;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.TicketRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;
import dev.kaiwen.eventpulse.service.BookingService;

/**
 * 用户自助取消订单后，票必须一起作废。
 *
 * 这条链路只有在真实数据库 + 真实事务里才能验证：{@code cancelConfirmed} 是
 * {@code @Modifying(clearAutomatically = true)}，会清空持久化上下文。如果票状态
 * 在它之后才修改，改的就是游离实体，更新被静默丢弃 —— 订单已退款、票却仍是
 * VALID，可以照常核销入场。Mock 仓库的测试看不出这个问题。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.profiles.active=api")
@Testcontainers(disabledWithoutDocker = true)
class BookingCancelTicketIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.POSTGRES.getPassword());
        registry.add("eventpulse.redis-enabled", () -> "false");
    }

    @Autowired
    BookingService bookings;
    @Autowired
    TicketRepository tickets;
    @Autowired
    EventRepository events;
    @Autowired
    UserRepository users;

    @AfterEach
    void clearContext() {
        BaseContext.clear();
    }

    @Test
    void cancellingABookingAlsoCancelsItsTickets() {
        Event event = persistedEvent();
        Long buyerId = persistedUser("USER", 100_000).getId();
        BaseContext.setUserId(buyerId);
        BaseContext.setRole("USER");

        Long bookingId = bookings.create(new CreateBookingRequest(event.getId(), 2), null).id();
        List<Ticket> issued = tickets.findByBookingIdOrderByIdAsc(bookingId);
        assertThat(issued).hasSize(2).allMatch(ticket -> TicketStatus.VALID.equals(ticket.getStatus()));

        bookings.cancel(bookingId);

        // 事务已提交，这里是全新读取：票必须已作废，否则退款后仍能核销入场。
        assertThat(tickets.findByBookingIdOrderByIdAsc(bookingId))
                .hasSize(2)
                .allMatch(ticket -> TicketStatus.CANCELLED.equals(ticket.getStatus()));
    }

    private Event persistedEvent() {
        Long organiserId = persistedUser("ORGANISER", 0).getId();
        Event event = new Event();
        event.setTitle("IT 取消退票 " + System.nanoTime());
        event.setDescription("it");
        event.setCategory("music");
        event.setCity("Shanghai");
        event.setStartsAt(Instant.now().plusSeconds(86_400));
        event.setEndsAt(Instant.now().plusSeconds(90_000));
        event.setPriceCents(100);
        event.setCapacity(10);
        event.setSold(0);
        event.setMaxQuantityPerBooking(10);
        event.setOrganiserId(organiserId);
        event.setStatus(EventStatus.PUBLISHED);
        event.setCreatedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        return events.save(event);
    }

    private User persistedUser(String role, long walletCents) {
        User user = new User();
        user.setEmail("it-cancel-" + System.nanoTime() + "@test.dev");
        user.setPassword("x");
        user.setName("IT");
        user.setRole(role);
        user.setWalletCents(walletCents);
        return users.save(user);
    }
}
