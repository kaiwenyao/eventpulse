package dev.kaiwen.eventpulse.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.EventDailyMetric;

public interface EventDailyMetricRepository extends JpaRepository<EventDailyMetric, EventDailyMetric.Key> {

    List<EventDailyMetric> findByEventIdAndMetricDateBetween(Long eventId, LocalDate from, LocalDate to);

    List<EventDailyMetric> findByEventIdInAndMetricDateBetween(List<Long> eventIds, LocalDate from, LocalDate to);
}
