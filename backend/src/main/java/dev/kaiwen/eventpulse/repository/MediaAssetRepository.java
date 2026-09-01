package dev.kaiwen.eventpulse.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.MediaAsset;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    Optional<MediaAsset> findByIdAndOwnerId(Long id, Long ownerId);
}
