package dev.kaiwen.eventpulse.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/**
 * 真实 S3（SeaweedFS）连通性验证。默认关闭：设置 MEDIA_S3_LIVE_TEST=true，
 * 并提供 MEDIA_S3_ENDPOINT / MEDIA_S3_BUCKET / MEDIA_S3_REGION /
 * MEDIA_S3_ACCESS_KEY / MEDIA_S3_SECRET_KEY 后才运行（CI 不需要、不执行）。
 *
 * 只操作独立临时前缀 {@code __eventpulse-selftest/<本次运行 uuid>/} 下的对象，
 * 结束时列出该前缀并逐个删除 —— 不触碰 bucket 里任何其他对象。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "MEDIA_S3_LIVE_TEST", matches = "true")
class S3LiveMediaStorageIT {

    private S3MediaStorage storage;
    private S3Client s3;
    private String bucket;
    private String prefix;

    @BeforeAll
    void connect() {
        String endpoint = System.getenv("MEDIA_S3_ENDPOINT");
        String region = envOr("MEDIA_S3_REGION", "us-east-1");
        bucket = envOr("MEDIA_S3_BUCKET", "eventpulse");
        String accessKey = System.getenv("MEDIA_S3_ACCESS_KEY");
        String secretKey = System.getenv("MEDIA_S3_SECRET_KEY");
        prefix = "__eventpulse-selftest/" + java.util.UUID.randomUUID() + "/";
        s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(15)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(15))
                        .build())
                .build();
        storage = new S3MediaStorage(s3, bucket);
    }

    @AfterAll
    void cleanupOnlyOwnPrefix() {
        // 只清理本次运行前缀下的对象：先删（storage.delete 幂等），再列出前缀
        // 内残留逐个删除，最后确认前缀已空。任何时刻都不涉及前缀外的对象。
        List<String> leftovers = listOwnPrefix();
        for (String key : leftovers) {
            storage.delete(key);
        }
        assertThat(listOwnPrefix()).isEmpty();
        s3.close();
    }

    @Test
    void putGetRoundTripPreservesContent() {
        String key = prefix + "roundtrip.png";
        storage.put(key, new byte[] {1, 2, 3, 4}, "image/png");
        assertThat(storage.get(key)).isEqualTo(new byte[] {1, 2, 3, 4});
    }

    @Test
    void missingObjectMapsToNotFoundSemantics() {
        String key = prefix + "never-existed.jpg";
        // 真实服务对不存在对象返回同样的异常语义，MediaService 把它映射成 404。
        assertThatThrownBy(() -> storage.get(key))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(((StorageException) e).getKind())
                        .isEqualTo(StorageException.Kind.OBJECT_NOT_FOUND));
    }

    @Test
    void deleteIsIdempotentAndRemovesObject() {
        String key = prefix + "delete-me.jpg";
        storage.put(key, new byte[] {5}, "image/jpeg");
        assertThat(storage.get(key)).isEqualTo(new byte[] {5});
        storage.delete(key);
        storage.delete(key); // 第二次删除不报错：幂等。
        assertThatThrownBy(() -> storage.get(key))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(((StorageException) e).getKind())
                        .isEqualTo(StorageException.Kind.OBJECT_NOT_FOUND));
    }

    private List<String> listOwnPrefix() {
        List<String> keys = new ArrayList<>();
        String continuation = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix);
            if (continuation != null) {
                request.continuationToken(continuation);
            }
            var page = s3.listObjectsV2(request.build());
            page.contents().forEach(o -> keys.add(o.key()));
            continuation = page.nextContinuationToken();
        } while (pageHasMore(continuation));
        return keys;
    }

    private static boolean pageHasMore(String continuation) {
        return continuation != null && !continuation.isBlank();
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}