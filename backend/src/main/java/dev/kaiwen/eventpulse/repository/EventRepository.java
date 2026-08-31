package dev.kaiwen.eventpulse.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.Event;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByStatusOrderByStartsAtAsc(String status);

    List<Event> findByOrganiserIdOrderByStartsAtDesc(Long organiserId);
}
