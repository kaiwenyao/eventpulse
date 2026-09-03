package dev.kaiwen.eventpulse.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import dev.kaiwen.eventpulse.common.AppProperties;

/**
 * 本地磁盘存储：仅当 eventpulse.s3.enabled=false（本地开发/单机试跑）时启用。
 * 目录仍由 eventpulse.media-dir 配置；k3s/compose 多副本部署必须用 S3，
 * 本地目录只存在于单个 Pod/容器的文件系统里。
 */
public class LocalStorageMediaStorage implements MediaStorage {

    private final AppProperties properties;

    public LocalStorageMediaStorage(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        Path dest = Path.of(properties.getMediaDir(), key);
        try {
            Files.createDirectories(dest.getParent());
            Files.write(dest, bytes);
        }
        catch (IOException e) {
            throw StorageException.unavailable("write", e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(Path.of(properties.getMediaDir(), key));
        }
        catch (NoSuchFileException e) {
            throw StorageException.objectNotFound(key, e);
        }
        catch (IOException e) {
            throw StorageException.unavailable("read", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(Path.of(properties.getMediaDir(), key));
        }
        catch (IOException e) {
            throw StorageException.unavailable("delete", e);
        }
    }
}