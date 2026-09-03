package dev.kaiwen.eventpulse.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.entity.MediaAsset;
import dev.kaiwen.eventpulse.repository.MediaAssetRepository;
import dev.kaiwen.eventpulse.storage.MediaStorage;
import dev.kaiwen.eventpulse.storage.StorageException;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 软删除图片的 S3 对象清理（仅 worker Profile）。
 *
 * DELETE /api/media/images/{id} 只做软删除（status=DELETED + deleted_at，
 * 保留审计与误删恢复窗口），对象本身由本任务在 eventpulse.media.purge-after-days
 * 宽限期后统一删除并标记 PURGED。删除失败的 key 本轮跳过、下轮重试；
 * 对象不存在视为已删除（S3 delete 幂等），多 Worker 并发清理同一对象也安全。
 *
 * 只处理数据库里 status=DELETED 且过了宽限期的记录，绝不碰其他对象。
 */
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "eventpulse.media", name = "purge-enabled", havingValue = "true", matchIfMissing = true)
public class MediaPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(MediaPurgeWorker.class);

    private final MediaAssetRepository assets;
    private final MediaStorage storage;
    private final AppProperties properties;
    private final MeterRegistry meters;

    public MediaPurgeWorker(MediaAssetRepository assets, MediaStorage storage,
                            AppProperties properties, MeterRegistry meters) {
        this.assets = assets;
        this.storage = storage;
        this.properties = properties;
        this.meters = meters;
    }

    @Scheduled(
            fixedDelayString = "${eventpulse.media.purge-fixed-delay-ms:3600000}",
            initialDelayString = "${eventpulse.media.purge-initial-delay-ms:60000}")
    public void purge() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(properties.getMedia().getPurgeAfterDays()));
        List<MediaAsset> batch = assets.findByStatusAndDeletedAtBefore("DELETED", cutoff,
                PageRequest.of(0, properties.getMedia().getPurgeBatchSize(), Sort.by("id").ascending()));
        if (batch.isEmpty()) {
            return;
        }
        int purged = 0;
        int failed = 0;
        for (MediaAsset asset : batch) {
            try {
                storage.delete(asset.getStorageKey());
            }
            catch (StorageException e) {
                // 存储不可用/对象删除失败：保持 DELETED，等下一轮重试，
                // 绝不把状态改成 PURGED 造成「数据库说清了、对象还在」的漂移。
                failed++;
                log.warn("Skip purging media object {} this round: {}", asset.getStorageKey(), e.getMessage());
                continue;
            }
            asset.setStatus("PURGED");
            assets.save(asset);
            purged++;
        }
        meters.counter("eventpulse.media.purge", "result", "purged").increment(purged);
        meters.counter("eventpulse.media.purge", "result", "failed").increment(failed);
        log.info("Media purge round: {} purged, {} skipped, cutoff {}", purged, failed, cutoff);
    }
}