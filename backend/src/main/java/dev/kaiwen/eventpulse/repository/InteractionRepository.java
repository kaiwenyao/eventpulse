package dev.kaiwen.eventpulse.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.Interaction;

public interface InteractionRepository extends JpaRepository<Interaction, Long> {

    List<Interaction> findByUserIdOrderByCreatedAtDesc(Long userId);
}
