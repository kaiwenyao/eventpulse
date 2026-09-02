package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.entity.OutboxEvent;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.outbox.OutboxRelay;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.OutboxRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;
import dev.kaiwen.eventpulse.worker.EventLifecycleWorker;

/**
 * 真实数据库上跑 worker 的后台任务：
 * - Relay 的领取-发送-标记链路；领取/释放是 @Modifying 查询，必须在事务里执行，
 *   这里的 Bean 都是 Spring 代理，能暴露「无事务」这类接线错误；
 * - 活动生命周期的条件更新推进 PUBLISHED → ONGOING → FINISHED。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.profiles.active=worker")
@Testcontainers(disabledWithoutDocker = true)
class WorkerBackgroundTasksIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.POSTGRES.getPassword());
        registry.add("eventpulse.redis-enabled", () -> "false");
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    OutboxRepository outbox;
    @Autowired
    OutboxRelay relay;
    @Autowired
    EventLifecycleWorker lifecycle;
    @Autowired
    EventRepository events;
    @Autowired
    UserRepository users;

    @Test
    void lifecycleConditionalUpdatesAdvanceStatuses() {
        Instant now = Instant.now();
        Event started = persistedEvent("IT 已开始", EventStatus.PUBLISHED, now.minusSeconds(3600), now.plusSeconds(3600));
        Event ended = persistedEvent("IT 已结束", EventStatus.ONGOING, now.minusSeconds(7200), now.minusSeconds(3600));
        Event untouched = persistedEvent("IT 未开始", EventStatus.PUBLISHED, now.plusSeconds(86400), now.plusSeconds(90000));

        lifecycle.advance();

        // 数据库条件更新：只有仍满足条件的行被推进，且方向只能前进。
        assertThat(events.findById(started.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.ONGOING);
        assertThat(events.findById(ended.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.FINISHED);
        assertThat(events.findById(untouched.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    private Event persistedEvent(String title, String status, Instant startsAt, Instant endsAt) {
        // events.organiser_id 外键指向 users：先建一个主办方。
        User organiser = new User();
        organiser.setEmail("it-lifecycle-" + System.nanoTime() + "@test.dev");
        organiser.setPassword("x");
        organiser.setName("IT");
        organiser.setRole("ORGANISER");
        organiser.setWalletCents(0);
        Long organiserId = users.save(organiser).getId();

        Event event = new Event();
        event.setTitle(title);
        event.setDescription("it");
        event.setCategory("music");
        event.setCity("Shanghai");
        event.setStartsAt(startsAt);
        event.setEndsAt(endsAt);
        event.setPriceCents(0);
        event.setCapacity(10);
        event.setSold(0);
        event.setMaxQuantityPerBooking(10);
        event.setOrganiserId(organiserId);
        event.setStatus(status);
        event.setCreatedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        return events.save(event);
    }

    @Test
    void relayClaimsSendsAndMarksPublished() {
        OutboxEvent event = new OutboxEvent();
        event.setTopic("booking-events");
        event.setEventType("BOOKING_CREATED");
        event.setMessageKey("booking:777");
        event.setDedupKey("BOOKING_CREATED:777");
        event.setPayload("{\"type\":\"BOOKING_CREATED\"}");
        outbox.save(event);

        Mockito.when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publish();

        OutboxEvent after = outbox.findById(event.getId()).orElseThrow();
        assertThat(after.getPublishedAt()).isNotNull();
        assertThat(after.getClaimedBy()).isNull();
        Mockito.verify(kafkaTemplate).send("booking-events", "booking:777", event.getPayload());
    }

    @Test
    void relayFailurePathKeepsMessagePending() {
        OutboxEvent event = new OutboxEvent();
        event.setTopic("booking-events");
        event.setEventType("BOOKING_CANCELLED");
        event.setMessageKey("booking:778");
        event.setDedupKey("BOOKING_CANCELLED:778");
        event.setPayload("{}");
        outbox.save(event);

        Mockito.when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("down")));

        relay.publish();

        OutboxEvent after = outbox.findById(event.getId()).orElseThrow();
        // 暂时故障：保持待发送，租约已释放，错误已记录，下一轮重试。
        assertThat(after.getPublishedAt()).isNull();
        assertThat(after.getFailedAt()).isNull();
        assertThat(after.getClaimedBy()).isNull();
        assertThat(after.getPublishAttempts()).isEqualTo(1);
        assertThat(after.getLastError()).isNotBlank();
    }
}
