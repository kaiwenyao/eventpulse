package dev.kaiwen.eventpulse.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.UserPreference;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {
}
