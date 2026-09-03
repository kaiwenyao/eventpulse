package dev.kaiwen.eventpulse.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.storage.MediaStorage;
import dev.kaiwen.eventpulse.storage.S3MediaStorage;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 对象存储装配（eventpulse.s3.enabled=true 时生效）。三个角色共用后端
 * 镜像：S3Client 只是本地对象，构造时不发起任何网络请求，所以 worker /
 * seeder 带着同一套环境变量启动也不会失败——只有真正访问图片的 api 和
 * 清理对象的 worker 会在请求时感知 S3 是否可用。
 *
 * 凭证从环境变量 / K8s Secret 注入（S3_ACCESS_KEY / S3_SECRET_KEY），
 * 这里只做缺失校验，不打印也不落日志。
 */
@Configuration
@ConditionalOnProperty(prefix = "eventpulse.s3", name = "enabled", havingValue = "true")
public class S3StorageConfig {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(AppProperties properties) {
        AppProperties.S3 s3 = properties.getS3();
        if (isBlank(s3.getEndpoint()) || isBlank(s3.getBucket())
                || isBlank(s3.getAccessKey()) || isBlank(s3.getSecretKey())) {
            throw new IllegalStateException(
                    "S3 storage is enabled but incomplete: set S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY and S3_SECRET_KEY");
        }
        return S3Client.builder()
                .endpointOverride(URI.create(s3.getEndpoint()))
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())))
                .forcePathStyle(s3.isPathStyleAccess())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofMillis(s3.getConnectTimeoutMs()))
                        .socketTimeout(Duration.ofMillis(s3.getReadTimeoutMs())))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMillis(s3.getApiCallTimeoutMs()))
                        .apiCallAttemptTimeout(Duration.ofMillis(s3.getReadTimeoutMs()))
                        .build())
                .build();
    }

    @Bean
    public MediaStorage s3MediaStorage(S3Client s3Client, AppProperties properties) {
        return new S3MediaStorage(s3Client, properties.getS3().getBucket());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}