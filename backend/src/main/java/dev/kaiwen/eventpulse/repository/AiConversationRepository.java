package dev.kaiwen.eventpulse.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.AiConversation;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {
}
