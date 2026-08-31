package dev.kaiwen.eventpulse.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByBookingIdInOrderByCreatedAtDesc(List<Long> bookingIds);

    List<Notification> findAllByOrderByCreatedAtDesc();
}
