package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;
import dev.kaiwen.eventpulse.service.JwtService;

/**
 * SSE 端点的错误响应必须是正常的 JSON + 正确状态码。
 *
 * 浏览器订阅时带 {@code Accept: text/event-stream}。如果端点又用 produces 把响应
 * 类型钉死成 text/event-stream，异常处理器就写不出 JSON（No acceptable
 * representation），所有业务错误都会塌成空 body 的 500。前端拿到 500 会当成
 * 临时故障无限重连，一个无权/不存在的订单页能永久刷屏。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=api")
@Testcontainers(disabledWithoutDocker = true)
class SseErrorResponseIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.POSTGRES.getPassword());
        registry.add("eventpulse.redis-enabled", () -> "false");
    }

    @LocalServerPort
    int port;
    @Autowired
    JwtService jwt;
    @Autowired
    UserRepository users;
    @Autowired
    EventRepository events;
    @Autowired
    BookingRepository bookings;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void subscribingToSomeoneElsesBookingReturnsForbiddenJson() throws Exception {
        Long ownerId = persistedUser("USER").getId();
        Long intruderId = persistedUser("USER").getId();
        Long bookingId = persistedBooking(ownerId);

        HttpResponse<String> response = subscribe(bookingId, jwt.createToken(intruderId, "USER"));

        assertThat(response.statusCode()).isEqualTo(403);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("code").asInt()).isZero();
        assertThat(body.path("msg").asText()).isEqualTo("You can only subscribe to your own bookings");
    }

    @Test
    void subscribingToAMissingBookingReturnsNotFoundJson() throws Exception {
        Long userId = persistedUser("USER").getId();

        HttpResponse<String> response = subscribe(9_999_999L, jwt.createToken(userId, "USER"));

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("code").asInt()).isZero();
        assertThat(body.path("msg").asText()).isEqualTo("Booking not found");
    }

    /** 和浏览器一样带 Accept: text/event-stream —— 这正是触发内容协商失败的条件。 */
    private HttpResponse<String> subscribe(Long bookingId, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/bookings/" + bookingId + "/events"))
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private User persistedUser(String role) {
        User user = new User();
        user.setEmail("it-sse-err-" + System.nanoTime() + "@test.dev");
        user.setPassword("x");
        user.setName("IT");
        user.setRole(role);
        user.setWalletCents(0);
        return users.save(user);
    }

    private Long persistedBooking(Long userId) {
        Event event = new Event();
        event.setTitle("IT SSE 错误 " + System.nanoTime());
        event.setDescription("it");
        event.setCategory("music");
        event.setCity("Shanghai");
        event.setStartsAt(Instant.now().plusSeconds(86_400));
        event.setEndsAt(Instant.now().plusSeconds(90_000));
        event.setPriceCents(0);
        event.setCapacity(10);
        event.setSold(0);
        event.setMaxQuantityPerBooking(10);
        event.setOrganiserId(persistedUser("ORGANISER").getId());
        event.setStatus(EventStatus.PUBLISHED);
        event.setCreatedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        Long eventId = events.save(event).getId();

        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setEventId(eventId);
        booking.setQuantity(1);
        booking.setPaidCents(0);
        booking.setStatus("CONFIRMED");
        booking.setCreatedAt(Instant.now());
        return bookings.save(booking).getId();
    }
}
