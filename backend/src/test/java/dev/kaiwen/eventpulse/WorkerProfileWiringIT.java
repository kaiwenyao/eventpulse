package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * worker Profile 装配检查：Kafka consumer、Outbox relay、生命周期任务、
 * SSE 提醒发布都在；业务 Controller、SSE 连接管理、Seeder 都不在。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.profiles.active=worker")
@Testcontainers(disabledWithoutDocker = true)
class WorkerProfileWiringIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.POSTGRES.getPassword());
        registry.add("eventpulse.redis-enabled", () -> "false");
    }

    @Autowired
    ApplicationContext ctx;

    @Test
    void workerLoadsOutboxConsumerAndLifecycle() {
        assertThat(ctx.containsBean("bookingConsumer")).isTrue();
        assertThat(ctx.containsBean("outboxRelay")).isTrue();
        assertThat(ctx.containsBean("outboxStatusService")).isTrue();
        assertThat(ctx.containsBean("eventLifecycleWorker")).isTrue();
        assertThat(ctx.containsBean("kafkaErrorHandlerConfig")).isTrue();
        assertThat(ctx.containsBean("bookingEventsTopic")).isTrue();
        assertThat(ctx.containsBean("bookingEventsDltTopic")).isTrue();
        assertThat(ctx.containsBean("sseReminderPublisher")).isTrue();
        // Actuator 健康检查端口保留。
        assertThat(ctx.containsBean("healthEndpoint")).isTrue();
    }

    @Test
    void workerDoesNotStartBusinessControllersOrSeeder() {
        assertThat(ctx.containsBean("platformController")).isFalse();
        assertThat(ctx.containsBean("authController")).isFalse();
        assertThat(ctx.containsBean("sseConnectionRegistry")).isFalse();
        assertThat(ctx.containsBean("sseSubscriptionService")).isFalse();
        assertThat(ctx.containsBean("sseEventSubscriber")).isFalse();
        assertThat(ctx.containsBean("seederService")).isFalse();
        assertThat(ctx.containsBean("dataSeeder")).isFalse();
    }
}
