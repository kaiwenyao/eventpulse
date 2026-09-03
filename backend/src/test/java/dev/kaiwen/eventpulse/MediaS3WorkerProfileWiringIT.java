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
 * worker Profile + S3 启用（k3s 上 worker 与 api 共用同一套 envFrom，S3 变量
 * 也在 worker 环境里）的启动兼容性：S3Client 构造不联网，清理任务照常装配。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=worker",
        "eventpulse.outbox.poll-ms=3600000",
        "eventpulse.s3.enabled=true",
        "eventpulse.s3.endpoint=http://127.0.0.1:9",
        "eventpulse.s3.bucket=eventpulse",
        "eventpulse.s3.access-key=it-access-key",
        "eventpulse.s3.secret-key=it-secret-key"})
@Testcontainers(disabledWithoutDocker = true)
class MediaS3WorkerProfileWiringIT {

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
    void workerBootsWithS3StorageAndPurgeWorker() {
        assertThat(ctx.containsBean("s3Client")).isTrue();
        assertThat(ctx.containsBean("s3MediaStorage")).isTrue();
        assertThat(ctx.containsBean("localMediaStorage")).isFalse();
        assertThat(ctx.containsBean("mediaPurgeWorker")).isTrue();
    }
}