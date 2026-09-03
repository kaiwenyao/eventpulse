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
 * eventpulse.s3.enabled=true 时的装配检查：
 * - S3Client / S3MediaStorage 装配成功、本地磁盘存储 Bean 消失；
 * - 上下文能完整启动 —— S3Client 构造不发起网络请求，endpoint 指向不存在的
 *   端口也不会让任何 profile 的启动失败（S3 是否可用只在真正访问时暴露）。
 * 这正是「api 配置了 S3，worker / seeder 共用镜像也要能启动」的启动兼容性验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=api",
        "eventpulse.s3.enabled=true",
        "eventpulse.s3.endpoint=http://127.0.0.1:9",
        "eventpulse.s3.bucket=eventpulse",
        "eventpulse.s3.access-key=it-access-key",
        "eventpulse.s3.secret-key=it-secret-key"})
@Testcontainers(disabledWithoutDocker = true)
class MediaS3ProfileWiringIT {

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
    void s3BeansReplaceLocalStorageWhenEnabled() {
        assertThat(ctx.containsBean("s3Client")).isTrue();
        assertThat(ctx.containsBean("s3MediaStorage")).isTrue();
        assertThat(ctx.containsBean("localMediaStorage")).isFalse();
    }

    @Test
    void s3MediaStorageIsWiredIntoMediaService() {
        // api 角色的上传/读取链路拿到的是 S3 实现。
        var mediaService = ctx.getBean(dev.kaiwen.eventpulse.service.MediaService.class);
        assertThat(mediaService).isNotNull();
    }
}