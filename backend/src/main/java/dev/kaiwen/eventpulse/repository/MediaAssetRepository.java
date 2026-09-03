package dev.kaiwen.eventpulse.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.MediaAsset;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    Optional<MediaAsset> findByIdAndOwnerId(Long id, Long ownerId);

    /** 软删除宽限期已过的资产，按 id 升序分批取，供 worker 清理 S3 对象。 */
    List<MediaAsset> findByStatusAndDeletedAtBefore(String status, Instant deletedAt, Pageable pageable);
}
