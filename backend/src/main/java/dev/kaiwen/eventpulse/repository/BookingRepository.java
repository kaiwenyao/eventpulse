package dev.kaiwen.eventpulse.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.Booking;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);

    long countByUserIdAndStatus(Long userId, String status);

    List<Booking> findByEventIdOrderByCreatedAtDesc(Long eventId);

    long countByEventIdAndStatus(Long eventId, String status);

    /**
     * Claims a confirmed booking for cancellation exactly once.  This prevents two concurrent
     * cancellation requests from both refunding the same payment.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE bookings
            SET status = 'CANCELLED', cancelled_at = now()
            WHERE id = :id AND status = 'CONFIRMED'
            """, nativeQuery = true)
    int cancelConfirmed(@Param("id") Long id);
}
