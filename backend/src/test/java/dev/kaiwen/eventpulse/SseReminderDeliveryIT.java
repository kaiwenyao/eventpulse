package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.web.servlet.mvc.method.annotation.CapturingEmitterHandler;
import dev.kaiwen.eventpulse.sse.SseConnectionRegistry;
import dev.kaiwen.eventpulse.sse.SseReminder;
import dev.kaiwen.eventpulse.sse.SseReminderPublisher;

/**
 * SSE 提醒链路（真实 Redis）：Worker 侧发布器 → Redis 广播 → api 订阅者
 * → 本机连接注册表 → 连接。同一条提醒重放只送达一次（eventId 去重）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.profiles.active=api")
@Testcontainers(disabledWithoutDocker = true)
class SseReminderDeliveryIT {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.POSTGRES.getPassword());
        registry.add("eventpulse.redis-enabled", () -> "true");
        registry.add("eventpulse.redis-host", redis::getHost);
        registry.add("eventpulse.redis-port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired
    SseConnectionRegistry registry;
    @Autowired
    StringRedisTemplate redisTemplate;
    @Autowired
    ObjectMapper objectMapper;

    @AfterEach
    void resetConnections() {
        registry.closeAll();
    }

    private SseReminderPublisher publisher() {
        SseReminderPublisher publisher = new SseReminderPublisher(objectMapper);
        publisher.setRedis(redisTemplate);
        return publisher;
    }

    @Test
    void reminderFromWorkerSideReachesTheLocalEmitter() {
        CapturingEmitterHandler handler = new CapturingEmitterHandler();
        handler.attachTo(registry.register(7L, 2L));

        publisher().remindBooking(7L, "BOOKING_CREATED", "delivery-it-1");

        await(() -> handler.received().stream().anyMatch(data -> data instanceof SseReminder reminder
                && "delivery-it-1".equals(reminder.eventId())
                && "BOOKING_CREATED".equals(reminder.type())
                && reminder.bookingId() == 7L));
    }

    @Test
    void duplicateRemindersAreDeliveredOnce() {
        CapturingEmitterHandler handler = new CapturingEmitterHandler();
        handler.attachTo(registry.register(8L, 2L));

        SseReminderPublisher publisher = publisher();
        publisher.remindBooking(8L, "BOOKING_CANCELLED", "delivery-it-2");
        await(() -> handler.received().stream().anyMatch(data -> data instanceof SseReminder reminder
                && "delivery-it-2".equals(reminder.eventId())));
        int afterFirst = handler.received().size();
        // 重放同一条提醒：订阅端去重，连接上不会出现第二次推送。
        publisher.remindBooking(8L, "BOOKING_CANCELLED", "delivery-it-2");
        sleep(Duration.ofMillis(500));
        assertThat(handler.received().size()).isEqualTo(afterFirst);
    }

    @Test
    void remindersForOtherBookingsDoNotReachThisConnection() {
        CapturingEmitterHandler handler = new CapturingEmitterHandler();
        handler.attachTo(registry.register(9L, 3L));

        publisher().remindBooking(10L, "BOOKING_CREATED", "delivery-it-3");
        sleep(Duration.ofMillis(500));
        assertThat(handler.received()).isEmpty();
        registry.closeAll();
        assertThat(registry.size()).isZero();
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(100));
        }
        throw new AssertionError("SSE reminder was not delivered within 5s");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
