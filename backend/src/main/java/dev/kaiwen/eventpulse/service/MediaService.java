package dev.kaiwen.eventpulse.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.entity.MediaAsset;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.MediaAssetRepository;

@Service
public class MediaService {

    private static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_BYTES = 2 * 1024 * 1024;

    private final MediaAssetRepository assets;
    private final AppProperties properties;

    public MediaService(MediaAssetRepository assets, AppProperties properties) {
        this.assets = assets;
        this.properties = properties;
    }

    @Transactional
    public MediaAsset upload(String filename, String contentType, byte[] bytes) {
        Long ownerId = BaseContext.getUserId();
        if (ownerId == null) {
            throw new BusinessException("请先登录");
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("请选择图片");
        }
        if (bytes.length > MAX_BYTES) {
            throw new BusinessException("图片不能超过 2MB");
        }
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (!ALLOWED.contains(type)) {
            throw new BusinessException("仅支持 JPEG、PNG 或 WebP");
        }
        String ext = type.endsWith("png") ? "png" : type.endsWith("webp") ? "webp" : "jpg";
        String key = UUID.randomUUID() + "-" + safeName(filename) + "." + ext;
        Path dest = Path.of(properties.getMediaDir(), key);
        try {
            Files.createDirectories(dest.getParent());
            Files.write(dest, bytes);
        }
        catch (IOException e) {
            throw new IllegalStateException("无法保存图片", e);
        }
        MediaAsset asset = new MediaAsset();
        asset.setOwnerId(ownerId);
        asset.setStorageKey(key);
        asset.setPublicUrl("/api/media/images/" + key);
        asset.setContentType(type);
        asset.setSizeBytes(bytes.length);
        asset.setStatus("ACTIVE");
        assets.save(asset);
        asset.setPublicUrl("/api/media/images/" + asset.getId());
        return asset;
    }

    public MediaAsset requireActive(Long id) {
        MediaAsset asset = assets.findById(id).orElseThrow(() -> BusinessException.notFound("图片不存在"));
        if (!"ACTIVE".equals(asset.getStatus())) {
            throw BusinessException.notFound("图片不存在");
        }
        return asset;
    }

    public byte[] readBytes(MediaAsset asset) {
        try {
            return Files.readAllBytes(Path.of(properties.getMediaDir(), asset.getStorageKey()));
        }
        catch (IOException e) {
            throw BusinessException.notFound("图片文件缺失");
        }
    }

    @Transactional
    public void delete(Long id) {
        Long ownerId = BaseContext.getUserId();
        if (ownerId == null) {
            throw new BusinessException("请先登录");
        }
        MediaAsset asset = assets.findById(id).orElseThrow(() -> BusinessException.notFound("图片不存在"));
        if (!ownerId.equals(asset.getOwnerId())) {
            throw BusinessException.forbidden("只能删除自己上传的图片");
        }
        asset.setStatus("DELETED");
        asset.setDeletedAt(java.time.Instant.now());
    }

    private static String safeName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "cover";
        }
        String base = Path.of(filename).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        return base.length() > 40 ? base.substring(0, 40) : base;
    }
}
