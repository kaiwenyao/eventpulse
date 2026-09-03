package dev.kaiwen.eventpulse.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.storage.LocalStorageMediaStorage;
import dev.kaiwen.eventpulse.storage.MediaStorage;

/**
 * 本地磁盘图片存储（eventpulse.s3.enabled=false，即默认）的兜底装配。
 * 仅面向本地开发 / 单机试跑；多副本部署必须启用 S3（见 S3StorageConfig）。
 */
@Configuration
@ConditionalOnProperty(prefix = "eventpulse.s3", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalMediaStorageConfig {

    @Bean
    public MediaStorage localMediaStorage(AppProperties properties) {
        return new LocalStorageMediaStorage(properties);
    }
}