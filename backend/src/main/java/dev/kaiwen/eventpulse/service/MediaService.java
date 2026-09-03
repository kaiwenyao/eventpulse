package dev.kaiwen.eventpulse.service;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.entity.MediaAsset;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.exception.StorageUnavailableException;
import dev.kaiwen.eventpulse.repository.MediaAssetRepository;
import dev.kaiwen.eventpulse.storage.MediaStorage;
import dev.kaiwen.eventpulse.storage.StorageException;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_BYTES = 2 * 1024 * 1024;

    private final MediaAssetRepository assets;
    private final MediaStorage storage;

    public MediaService(MediaAssetRepository assets, MediaStorage storage) {
        this.assets = assets;
        this.storage = storage;
    }

    @Transactional
    public MediaAsset upload(String filename, String contentType, byte[] bytes) {
        Long ownerId = BaseContext.getUserId();
        if (ownerId == null) {
            throw new BusinessException("Please sign in");
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("Please choose an image");
        }
        if (bytes.length > MAX_BYTES) {
            throw new BusinessException("Image must be 2MB or smaller");
        }
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (!ALLOWED.contains(type)) {
            throw new BusinessException("Only JPEG, PNG, or WebP is supported");
        }
        String ext = type.endsWith("png") ? "png" : type.endsWith("webp") ? "webp" : "jpg";
        // 对象 key 由后端生成：UUID 保证唯一，用户文件名只作清洗后的可读后缀，
        // 绝不直接用原始文件名当对象路径。
        String key = UUID.randomUUID() + "-" + safeName(filename) + "." + ext;
        try {
            storage.put(key, bytes, type);
        }
        catch (StorageException e) {
            throw new StorageUnavailableException("Image storage is temporarily unavailable", e);
        }
        MediaAsset asset = new MediaAsset();
        asset.setOwnerId(ownerId);
        asset.setStorageKey(key);
        asset.setPublicUrl("/api/media/images/" + key);
        asset.setContentType(type);
        asset.setSizeBytes(bytes.length);
        asset.setStatus("ACTIVE");
        try {
            assets.save(asset);
        }
        catch (RuntimeException e) {
            // 数据库保存失败不能指望事务回滚 S3：这里补偿删除刚上传的对象。
            // key 是本次新生成的 UUID，删除不会波及任何已有对象；
            // 补偿也失败时只能留成孤儿对象，打日志人工排查（对象无数据库记录，
            // 不会被读取，也不会被清理任务选中）。
            deleteQuietly(key);
            throw e;
        }
        asset.setPublicUrl("/api/media/images/" + asset.getId());
        return asset;
    }

    public MediaAsset requireActive(Long id) {
        MediaAsset asset = assets.findById(id).orElseThrow(() -> BusinessException.notFound("Image not found"));
        if (!"ACTIVE".equals(asset.getStatus())) {
            throw BusinessException.notFound("Image not found");
        }
        return asset;
    }

    public byte[] readBytes(MediaAsset asset) {
        try {
            return storage.get(asset.getStorageKey());
        }
        catch (StorageException e) {
            if (e.getKind() == StorageException.Kind.OBJECT_NOT_FOUND) {
                throw BusinessException.notFound("Image file is missing");
            }
            throw new StorageUnavailableException("Image storage is temporarily unavailable", e);
        }
    }

    /**
     * 软删除：只改数据库状态与审计字段，S3 对象留给 worker 的清理任务在
     * 宽限期后统一删除（见 MediaPurgeWorker），期间可用数据库恢复找回。
     */
    @Transactional
    public void delete(Long id) {
        Long ownerId = BaseContext.getUserId();
        if (ownerId == null) {
            throw new BusinessException("Please sign in");
        }
        MediaAsset asset = assets.findById(id).orElseThrow(() -> BusinessException.notFound("Image not found"));
        if (!ownerId.equals(asset.getOwnerId())) {
            throw BusinessException.forbidden("You can only delete images you uploaded");
        }
        asset.setStatus("DELETED");
        asset.setDeletedAt(java.time.Instant.now());
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        }
        catch (RuntimeException e) {
            log.warn("Failed to clean up uploaded media object {} after database error; it stays orphaned", key, e);
        }
    }

    private static String safeName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "cover";
        }
        String base = java.nio.file.Path.of(filename).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        return base.length() > 40 ? base.substring(0, 40) : base;
    }
}