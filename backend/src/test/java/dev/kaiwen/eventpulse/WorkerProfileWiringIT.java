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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=worker",
        // 纯装配检查，relay 不需要真跑。上下文测完会留在 JVM 级缓存里，若 relay
        // 按默认 1s 轮询共享的 SharedPostgres，会在 WorkerBackgroundTasksIT 断言
        // outbox 行状态的间隙抢领消息（claimed_by 忽而非空），造成概率性失败。
        "eventpulse.outbox.poll-ms=3600000"})
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
        // 软删除图片的 S3 对象清理任务也只在 worker 上跑。
        assertThat(ctx.containsBean("mediaPurgeWorker")).isTrue();
        // Actuator 健康检查端口保留。
        assertThat(ctx.containsBean("healthEndpoint")).isTrue();
        // 默认 s3.enabled=false：worker 带着本地磁盘 MediaStorage 也能完整启动，
        // api 的 S3 配置不会把它拖起来（清理任务只对已配置的存储生效）。
        assertThat(ctx.containsBean("localMediaStorage")).isTrue();
        assertThat(ctx.containsBean("s3Client")).isFalse();
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
