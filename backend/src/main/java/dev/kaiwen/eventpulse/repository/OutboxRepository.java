package dev.kaiwen.eventpulse.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.OutboxEvent;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByIdAsc();

    long countByPublishedAtIsNull();
}
