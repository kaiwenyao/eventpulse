package dev.kaiwen.eventpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.entity.MediaAsset;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.exception.StorageUnavailableException;
import dev.kaiwen.eventpulse.repository.MediaAssetRepository;
import dev.kaiwen.eventpulse.storage.InMemoryMediaStorage;

/**
 * MediaService 的对象存储语义：上传校验、key 生成、Content-Type、
 * 「DB 失败补偿删除」「读取缺失 / 存储不可用」的映射、软删除不碰对象。
 */
class MediaServiceTest {

    private InMemoryMediaStorage storage;
    private MediaAssetRepository repo;
    private MediaService media;

    @BeforeEach
    void setUp() {
        storage = new InMemoryMediaStorage();
        repo = mock(MediaAssetRepository.class);
        org.mockito.Mockito.when(repo.save(any())).thenAnswer(inv -> {
            MediaAsset asset = inv.getArgument(0);
            // 重新打桩（when(repo.save(...))）会带着 null 实参再次走进这里，判空。
            if (asset != null && asset.getId() == null) {
                asset.setId(42L);
            }
            return asset;
        });
        media = new MediaService(repo, storage);
        BaseContext.setUserId(7L);
    }

    @AfterEach
    void tearDown() {
        BaseContext.clear();
    }

    @Test
    void uploadStoresDirectPublicUrlWhenStorageHasOne() {
        // 公开直连：public_url 指向对象存储，图片字节不再经过 api。
        storage.publicBaseUrl = "https://s3.kaiwen.dev/eventpulse";
        MediaAsset asset = media.upload("cover.png", "image/png", new byte[] {1});
        String key = storage.objects.keySet().iterator().next();
        assertThat(asset.getPublicUrl()).isEqualTo("https://s3.kaiwen.dev/eventpulse/" + key);
    }

    @Test
    void uploadFallsBackToProxyUrlWhenStorageHasNoPublicUrl() {
        // 本地磁盘 / 未配公开基址：仍旧下发代理路径，按自增 id 寻址。
        MediaAsset asset = media.upload("cover.png", "image/png", new byte[] {1});
        assertThat(asset.getPublicUrl()).isEqualTo("/api/media/images/42");
    }

    @Test
    void uploadValidatesLoginSizeAndType() {
        BaseContext.clear();
        assertThatThrownBy(() -> media.upload("a.png", "image/png", new byte[] {1}))
                .isInstanceOf(BusinessException.class);
        BaseContext.setUserId(2L);
        assertThatThrownBy(() -> media.upload("a.png", "image/png", new byte[0]))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> media.upload("a.png", "image/png", new byte[3 * 1024 * 1024]))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> media.upload("a.png", "text/plain", new byte[] {1}))
                .isInstanceOf(BusinessException.class);
        assertThat(storage.objects).isEmpty();
        org.mockito.Mockito.verify(repo, never()).save(any());
    }

    @Test
    void uploadGeneratesBackendKeyAndStoresContentType() {
        MediaAsset asset = media.upload("封面照.png", "image/png", new byte[] {1, 2, 3});
        assertThat(asset.getId()).isEqualTo(42L);
        assertThat(asset.getPublicUrl()).isEqualTo("/api/media/images/42");
        assertThat(asset.getContentType()).isEqualTo("image/png");
        assertThat(asset.getSizeBytes()).isEqualTo(3);
        assertThat(asset.getStatus()).isEqualTo("ACTIVE");
        String key = asset.getStorageKey();
        // UUID 前缀 + 清洗后的可读后缀（沿用既有行为：清洗名 + 生成的扩展名），
        // 绝不直接用原始文件名当对象路径。
        assertThat(key).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-___.png.png");
        assertThat(storage.objects.get(key)).isEqualTo(new byte[] {1, 2, 3});
        assertThat(storage.contentTypes.get(key)).isEqualTo("image/png");
        // key 由后端生成且唯一：两次上传同名文件也不重名。
        MediaAsset second = media.upload("封面照.png", "image/png", new byte[] {1});
        assertThat(second.getStorageKey()).isNotEqualTo(asset.getStorageKey());
    }

    @Test
    void uploadWithoutFilenameStillStoresObject() {
        MediaAsset asset = media.upload(null, "image/jpeg", new byte[] {1, 2});
        assertThat(asset.getStorageKey()).endsWith("-cover.jpg");
        assertThat(storage.objects.get(asset.getStorageKey())).isEqualTo(new byte[] {1, 2});
    }

    @Test
    void uploadCompensatesObjectWhenDatabaseSaveFails() {
        when(repo.save(any())).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> media.upload("a.png", "image/png", new byte[] {1, 2}))
                .isInstanceOf(IllegalStateException.class);
        // 数据库失败不能指望事务回滚 S3：刚上传的对象被补偿删除。
        assertThat(storage.objects).isEmpty();
    }

    @Test
    void uploadKeepsOrphanWhenCompensationAlsoFails() {
        when(repo.save(any())).thenThrow(new IllegalStateException("db down"));
        storage.failOnDelete = true;
        assertThatThrownBy(() -> media.upload("a.png", "image/png", new byte[] {1, 2}))
                .isInstanceOf(IllegalStateException.class);
        // 补偿失败留下孤儿对象（无数据库记录），请求本身仍然失败上报。
        assertThat(storage.objects).hasSize(1);
    }

    @Test
    void uploadStorageOutageFailsWithoutTouchingDatabase() {
        storage.failOnPut = true;
        assertThatThrownBy(() -> media.upload("a.png", "image/png", new byte[] {1, 2}))
                .isInstanceOf(StorageUnavailableException.class);
        org.mockito.Mockito.verify(repo, never()).save(any());
        assertThat(storage.objects).isEmpty();
    }

    @Test
    void readBytesMapsMissingObjectToNotFound() {
        MediaAsset asset = asset("gone-key.jpg", "ACTIVE");
        assertThatThrownBy(() -> media.readBytes(asset))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Image file is missing");
    }

    @Test
    void readBytesMapsStorageOutageToUnavailable() {
        MediaAsset asset = asset("k.jpg", "ACTIVE");
        storage.objects.put("k.jpg", new byte[] {1});
        storage.failOnGet = true;
        assertThatThrownBy(() -> media.readBytes(asset))
                .isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void readBytesReturnsObjectContent() {
        MediaAsset asset = asset("k.webp", "ACTIVE");
        storage.objects.put("k.webp", new byte[] {9, 8, 7});
        assertThat(media.readBytes(asset)).isEqualTo(new byte[] {9, 8, 7});
    }

    @Test
    void requireActiveRejectsDeletedAssets() {
        MediaAsset asset = asset("k.jpg", "DELETED");
        when(repo.findById(7L)).thenReturn(java.util.Optional.of(asset));
        assertThatThrownBy(() -> media.requireActive(7L)).isInstanceOf(BusinessException.class);
        asset.setStatus("ACTIVE");
        assertThat(media.requireActive(7L)).isSameAs(asset);
        when(repo.findById(8L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> media.requireActive(8L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteIsSoftAndDoesNotTouchStorage() {
        BaseContext.setUserId(2L);
        MediaAsset asset = asset("k.jpg", "ACTIVE");
        asset.setOwnerId(2L);
        when(repo.findById(9L)).thenReturn(java.util.Optional.of(asset));
        media.delete(9L);
        assertThat(asset.getStatus()).isEqualTo("DELETED");
        assertThat(asset.getDeletedAt()).isNotNull();
        assertThat(storage.objects).isEmpty();
        verify(repo).findById(9L);
    }

    @Test
    void deleteRequiresLoginAndOwnership() {
        BaseContext.clear();
        assertThatThrownBy(() -> media.delete(1L)).isInstanceOf(BusinessException.class);
        BaseContext.setUserId(3L);
        MediaAsset asset = asset("k.jpg", "ACTIVE");
        asset.setOwnerId(2L);
        when(repo.findById(1L)).thenReturn(java.util.Optional.of(asset));
        assertThatThrownBy(() -> media.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("You can only delete images you uploaded");
        assertThat(asset.getStatus()).isEqualTo("ACTIVE");
    }

    private static MediaAsset asset(String key, String status) {
        MediaAsset asset = new MediaAsset();
        asset.setId(7L);
        asset.setOwnerId(2L);
        asset.setStorageKey(key);
        asset.setPublicUrl("/api/media/images/7");
        asset.setContentType("image/jpeg");
        asset.setSizeBytes(3);
        asset.setStatus(status);
        return asset;
    }
}