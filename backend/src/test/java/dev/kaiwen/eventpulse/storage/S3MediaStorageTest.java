package dev.kaiwen.eventpulse.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3MediaStorage 把 SDK 异常翻译成两种语义：对象不存在（404）→
 * OBJECT_NOT_FOUND，其余（403 凭证错误、网络故障、5xx）→ UNAVAILABLE；
 * delete 幂等。同时校验 bucket/key/Content-Type 传递正确。
 */
class S3MediaStorageTest {

    private S3Client s3;
    private S3MediaStorage storage;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        storage = new S3MediaStorage(s3, "eventpulse", "https://s3.kaiwen.dev/eventpulse");
    }

    @Test
    void publicUrlJoinsBaseUrlAndKey() {
        assertThat(storage.publicUrl("seed/demo-covers/01.jpeg"))
                .contains("https://s3.kaiwen.dev/eventpulse/seed/demo-covers/01.jpeg");
    }

    @Test
    void publicUrlIgnoresTrailingSlashOnBaseUrl() {
        S3MediaStorage trailing = new S3MediaStorage(s3, "eventpulse", "https://s3.kaiwen.dev/eventpulse/");
        assertThat(trailing.publicUrl("a/b.jpg")).contains("https://s3.kaiwen.dev/eventpulse/a/b.jpg");
    }

    @Test
    void publicUrlEmptyWhenBaseUrlNotConfigured() {
        // 未配公开基址（bucket 仍是私有的）：调用方必须回落到代理路径，
        // 绝不能拼出一个匿名访问会 403 的地址。
        assertThat(new S3MediaStorage(s3, "eventpulse", "").publicUrl("a/b.jpg")).isEmpty();
        assertThat(new S3MediaStorage(s3, "eventpulse", null).publicUrl("a/b.jpg")).isEmpty();
    }

    @Test
    void putSetsImmutableCacheControl() {
        // key 含 UUID，内容永不变更：可长期缓存，浏览器/CDN 不必回源。
        storage.put("a/b.jpg", new byte[] {1}, "image/jpeg");
        assertThat(capturePut().cacheControl()).isEqualTo("public, max-age=31536000, immutable");
    }

    @Test
    void putSendsBucketKeyAndContentType() {
        storage.put("a/b.jpg", new byte[] {1}, "image/jpeg");
        PutObjectRequest request = capturePut();
        assertThat(request.bucket()).isEqualTo("eventpulse");
        assertThat(request.key()).isEqualTo("a/b.jpg");
        assertThat(request.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void getTranslatesNoSuchKeyToObjectNotFound() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("The specified key does not exist.").build());
        assertThatThrownBy(() -> storage.get("missing.jpg"))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(((StorageException) e).getKind())
                        .isEqualTo(StorageException.Kind.OBJECT_NOT_FOUND));
    }

    @Test
    void getTranslatesCredentialErrorsToUnavailable() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(S3Exception.builder()
                .statusCode(403).message("InvalidAccessKeyId").build());
        assertThatThrownBy(() -> storage.get("k.jpg"))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(((StorageException) e).getKind())
                        .isEqualTo(StorageException.Kind.UNAVAILABLE));
    }

    @Test
    void getTranslatesNetworkErrorsToUnavailable() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(SdkException.create("connect timeout", new RuntimeException()));
        assertThatThrownBy(() -> storage.get("k.jpg"))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(((StorageException) e).getKind())
                        .isEqualTo(StorageException.Kind.UNAVAILABLE));
    }

    @Test
    void deleteTreats404AsIdempotentSuccess() {
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("NoSuchKey").build());
        storage.delete("already-gone.jpg");
    }

    @Test
    void deleteTranslatesOtherErrorsToUnavailable() {
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(503).message("slow down").build());
        assertThatThrownBy(() -> storage.delete("k.jpg"))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(((StorageException) e).getKind())
                        .isEqualTo(StorageException.Kind.UNAVAILABLE));
    }

    @Test
    void getReturnsObjectBytes() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), new byte[] {9, 8, 7}));
        assertThat(storage.get("k.jpg")).isEqualTo(new byte[] {9, 8, 7});
    }

    private PutObjectRequest capturePut() {
        org.mockito.ArgumentCaptor<PutObjectRequest> key =
                org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(key.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        return key.getValue();
    }
}