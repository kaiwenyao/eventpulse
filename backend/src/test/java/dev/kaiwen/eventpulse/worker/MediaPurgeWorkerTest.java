package dev.kaiwen.eventpulse.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.entity.MediaAsset;
import dev.kaiwen.eventpulse.repository.MediaAssetRepository;
import dev.kaiwen.eventpulse.storage.InMemoryMediaStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 清理任务语义：只清「status=DELETED 且过宽限期」的记录；对象删除成功才标记
 * PURGED；删除失败本轮跳过、状态保持 DELETED 等重试；批量大小与排序生效。
 */
class MediaPurgeWorkerTest {

    private InMemoryMediaStorage storage;
    private MediaAssetRepository repo;
    private MediaPurgeWorker worker;

    @BeforeEach
    void setUp() {
        storage = new InMemoryMediaStorage();
        repo = org.mockito.Mockito.mock(MediaAssetRepository.class);
        AppProperties props = new AppProperties();
        props.getMedia().setPurgeAfterDays(7);
        props.getMedia().setPurgeBatchSize(2);
        worker = new MediaPurgeWorker(repo, storage, props, new SimpleMeterRegistry());
    }

    @Test
    void purgesOnlyDeletedAssetsPastGraceAndMarksThemPurged() {
        MediaAsset first = deletedAsset(1L, "old.jpg");
        MediaAsset second = deletedAsset(2L, "old2.jpg");
        when(repo.findByStatusAndDeletedAtBefore(any(String.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        worker.purge();

        assertThat(first.getStatus()).isEqualTo("PURGED");
        assertThat(second.getStatus()).isEqualTo("PURGED");
        assertThat(storage.objects).doesNotContainKeys("old.jpg", "old2.jpg");
        verify(repo, times(2)).save(any(MediaAsset.class));
    }

    @Test
    void storageFailureKeepsStatusDeletedForRetry() {
        MediaAsset broken = deletedAsset(1L, "broken.jpg");
        MediaAsset healthy = deletedAsset(2L, "healthy.jpg");
        when(repo.findByStatusAndDeletedAtBefore(any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(broken, healthy));
        // 只对 broken.jpg 注入删除失败，healthy 照常成功（部分失败场景）。
        storage.failDeleteKeys.add("broken.jpg");

        worker.purge();

        assertThat(broken.getStatus()).isEqualTo("DELETED");
        assertThat(broken.getDeletedAt()).isNotNull();
        assertThat(healthy.getStatus()).isEqualTo("PURGED");
        verify(repo, times(1)).save(healthy);
        verify(repo, never()).save(broken);
    }

    @Test
    void queriesDeletedStatusWithConfiguredBatchAndIdSort() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        when(repo.findByStatusAndDeletedAtBefore(status.capture(), any(Instant.class), pageable.capture()))
                .thenReturn(List.of());

        worker.purge();

        assertThat(status.getValue()).isEqualTo("DELETED");
        assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by("id").ascending());
    }

    @Test
    void emptyBatchMakesNoStorageCalls() {
        when(repo.findByStatusAndDeletedAtBefore(any(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        worker.purge();
        assertThat(storage.objects).isEmpty();
    }

    private static MediaAsset deletedAsset(Long id, String key) {
        MediaAsset asset = new MediaAsset();
        asset.setId(id);
        asset.setOwnerId(2L);
        asset.setStorageKey(key);
        asset.setPublicUrl("/api/media/images/" + id);
        asset.setContentType("image/jpeg");
        asset.setSizeBytes(3);
        asset.setStatus("DELETED");
        asset.setDeletedAt(Instant.now());
        return asset;
    }
}