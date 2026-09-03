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
 * api Profile 装配检查：Controller / SSE 组件存在；
 * Kafka consumer、Outbox relay、生命周期任务、Seeder 一律不存在。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.profiles.active=api")
@Testcontainers(disabledWithoutDocker = true)
class ApiProfileWiringIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.POSTGRES.getPassword());
        // 单元环境下不起 Redis：api 的 Redis 组件按条件装配的行为单独测。
        registry.add("eventpulse.redis-enabled", () -> "false");
    }

    @Autowired
    ApplicationContext ctx;

    @Test
    void apiLoadsControllersAndSseComponents() {
        assertThat(ctx.containsBean("authController")).isTrue();
        assertThat(ctx.containsBean("platformController")).isTrue();
        assertThat(ctx.containsBean("sseConnectionRegistry")).isTrue();
        assertThat(ctx.containsBean("sseNotificationService")).isTrue();
        assertThat(ctx.containsBean("sseSubscriptionService")).isTrue();
        assertThat(ctx.containsBean("sseEventSubscriber")).isTrue();
    }

    @Test
    void apiDoesNotStartWorkerOrSeederComponents() {
        assertThat(ctx.containsBean("bookingConsumer")).isFalse();
        assertThat(ctx.containsBean("outboxRelay")).isFalse();
        assertThat(ctx.containsBean("eventLifecycleWorker")).isFalse();
        assertThat(ctx.containsBean("kafkaErrorHandlerConfig")).isFalse();
        assertThat(ctx.containsBean("sseReminderPublisher")).isFalse();
        assertThat(ctx.containsBean("seederService")).isFalse();
        assertThat(ctx.containsBean("dataSeeder")).isFalse();
        // Kafka 自动装配被排除：api 实例上没有任何 Kafka Bean。
        assertThat(ctx.containsBean("kafkaTemplate")).isFalse();
    }

    @Test
    void defaultMediaStorageIsLocalDisk() {
        // s3.enabled 默认 false：本地开发回落磁盘存储，S3 相关 Bean 不存在。
        assertThat(ctx.containsBean("localMediaStorage")).isTrue();
        assertThat(ctx.containsBean("s3Client")).isFalse();
        assertThat(ctx.containsBean("s3MediaStorage")).isFalse();
    }
}
