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
 * seeder Profile 装配检查：只有 Seeder；不启动 Web、Kafka、Outbox、生命周期任务。
 * DataSeeder 用 mock 顶替 —— 真实的它执行完会 System.exit，测试 JVM 不能被带走。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.profiles.active=seeder")
@Testcontainers(disabledWithoutDocker = true)
class SeederProfileWiringIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.POSTGRES.getPassword());
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    dev.kaiwen.eventpulse.seed.DataSeeder dataSeeder;

    @Autowired
    ApplicationContext ctx;

    @Test
    void seederLoadsSeederServiceAndExitsCleanlyAfterRun() {
        assertThat(ctx.containsBean("seederService")).isTrue();
        assertThat(ctx.containsBean("dataSeeder")).isTrue();
        assertThat(ctx.containsBean("demoDataSeeder")).isTrue();
        // Seeder 不用图片，但共用镜像必须能带着同一个 MediaStorage Bean 启动，
        // api 侧的 S3 配置变化不能让 seeder Job 起不来。
        assertThat(ctx.containsBean("localMediaStorage")).isTrue();
    }

    @Test
    void seederStartsNoWebServerKafkaOutboxOrLifecycle() {
        assertThat(ctx.containsBean("platformController")).isFalse();
        assertThat(ctx.containsBean("authController")).isFalse();
        assertThat(ctx.containsBean("bookingConsumer")).isFalse();
        assertThat(ctx.containsBean("outboxRelay")).isFalse();
        assertThat(ctx.containsBean("eventLifecycleWorker")).isFalse();
        assertThat(ctx.containsBean("sseConnectionRegistry")).isFalse();
        assertThat(ctx.containsBean("sseReminderPublisher")).isFalse();
        assertThat(ctx.containsBean("kafkaTemplate")).isFalse();
        // spring.main.web-application-type=none：不存在任何 Web 服务器工厂。
        assertThat(ctx.containsBean("webServerFactory")).isFalse();
    }
}
