package dev.kaiwen.eventpulse.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.EventAuditLog;

public interface EventAuditLogRepository extends JpaRepository<EventAuditLog, Long> {

    List<EventAuditLog> findTop20ByEventIdOrderByCreatedAtDesc(Long eventId);
}
