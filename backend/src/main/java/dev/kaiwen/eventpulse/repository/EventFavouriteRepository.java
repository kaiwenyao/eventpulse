package dev.kaiwen.eventpulse.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.EventFavourite;

public interface EventFavouriteRepository extends JpaRepository<EventFavourite, EventFavourite.Key> {

    boolean existsByUserIdAndEventId(Long userId, Long eventId);

    List<EventFavourite> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);

    void deleteByUserIdAndEventId(Long userId, Long eventId);
}
