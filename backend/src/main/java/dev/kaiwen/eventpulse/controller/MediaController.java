package dev.kaiwen.eventpulse.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.entity.MediaAsset;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.exception.StorageUnavailableException;
import dev.kaiwen.eventpulse.service.MediaService;

@RestController
@Profile("api")
@RequestMapping("/api/media/images")
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @PostMapping
    public Result<MediaAsset> upload(@RequestPart("file") MultipartFile file) {
        try {
            return Result.success(media.upload(file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        }
        catch (BusinessException | StorageUnavailableException e) {
            // 业务校验失败 / 对象存储不可用：保留真实状态码与文案，
            // 不把 503 归并成 400。
            throw e;
        }
        catch (Exception e) {
            throw new BusinessException("Upload failed");
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable Long id) {
        MediaAsset asset = media.requireActive(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .body(media.readBytes(asset));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        media.delete(id);
        return Result.success();
    }
}
