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
 * seeder Profile + S3 启用的启动兼容性：k3s 上三个角色共用同一套 envFrom，
 * api 的 S3 配置不能让 seeder Job 启动失败。S3Client 构造不联网，endpoint
 * 指向不存在的端口也能完整起动机（真实可用性只在真正访问时暴露）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=seeder",
        "eventpulse.s3.enabled=true",
        "eventpulse.s3.endpoint=http://127.0.0.1:9",
        "eventpulse.s3.bucket=eventpulse",
        "eventpulse.s3.access-key=it-access-key",
        "eventpulse.s3.secret-key=it-secret-key"})
@Testcontainers(disabledWithoutDocker = true)
class MediaS3SeederProfileWiringIT {

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
    void seederBootsWithS3StorageConfigured() {
        assertThat(ctx.containsBean("s3Client")).isTrue();
        assertThat(ctx.containsBean("s3MediaStorage")).isTrue();
        assertThat(ctx.containsBean("localMediaStorage")).isFalse();
        assertThat(ctx.containsBean("seederService")).isTrue();
    }
}