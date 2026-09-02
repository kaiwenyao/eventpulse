package dev.kaiwen.eventpulse.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.AiRequestLog;

public interface AiRequestLogRepository extends JpaRepository<AiRequestLog, String> {
}
