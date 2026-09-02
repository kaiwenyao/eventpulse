package dev.kaiwen.eventpulse.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.SeedRun;

public interface SeedRunRepository extends JpaRepository<SeedRun, String> {
}
