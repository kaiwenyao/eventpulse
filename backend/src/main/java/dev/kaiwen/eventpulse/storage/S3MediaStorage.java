package dev.kaiwen.eventpulse.storage;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 兼容对象存储（SeaweedFS / MinIO / AWS 均可）。S3Client 由
 * {@code S3StorageConfig} 构建并复用（进程级单例、带超时、停机时 close）。
 * 这里只做两件事：对象读写删 + 把 SDK 异常翻译成 {@link StorageException}
 * 的两种语义（对象不存在 / 服务不可用），不向上层泄露 SDK 类型。
 */
public class S3MediaStorage implements MediaStorage {

    private final S3Client s3;
    private final String bucket;

    public S3MediaStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(bytes));
        }
        catch (S3Exception e) {
            throw translate("upload", key, e);
        }
        catch (SdkException e) {
            throw StorageException.unavailable("upload", e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build()).asByteArray();
        }
        catch (NoSuchKeyException e) {
            throw StorageException.objectNotFound(key, e);
        }
        catch (S3Exception e) {
            throw translate("read", key, e);
        }
        catch (SdkException e) {
            throw StorageException.unavailable("read", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        }
        catch (S3Exception e) {
            if (e.statusCode() == 404) {
                // 对象已不存在：delete 语义幂等，清理任务重放不会报错。
                return;
            }
            throw translate("delete", key, e);
        }
        catch (SdkException e) {
            throw StorageException.unavailable("delete", e);
        }
    }

    private static StorageException translate(String operation, String key, S3Exception e) {
        if (e.statusCode() == 404) {
            return StorageException.objectNotFound(key, e);
        }
        // 403（凭证错误/无权限）、5xx、限流等都归为「存储暂不可用」：
        // message 不带 SDK 原文，避免把访问细节带进响应；cause 留给服务端日志。
        return StorageException.unavailable(operation, e);
    }
}