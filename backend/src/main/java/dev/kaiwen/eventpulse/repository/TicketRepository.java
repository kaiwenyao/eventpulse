package dev.kaiwen.eventpulse.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.kaiwen.eventpulse.entity.Ticket;

import jakarta.persistence.LockModeType;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByBookingIdOrderByIdAsc(Long bookingId);

    List<Ticket> findByEventIdOrderByIdAsc(Long eventId);

    Optional<Ticket> findByTicketCodeHash(String ticketCodeHash);

    /** Locks every ticket in an order before deciding whether that order can be refunded. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.bookingId = :bookingId ORDER BY t.id")
    List<Ticket> findByBookingIdForUpdate(@Param("bookingId") Long bookingId);

    /** Makes check-in serialize with cancellation for the same ticket. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.ticketCodeHash = :ticketCodeHash")
    Optional<Ticket> findByTicketCodeHashForUpdate(@Param("ticketCodeHash") String ticketCodeHash);

    long countByEventIdAndStatus(Long eventId, String status);

    long countByBookingIdAndStatus(Long bookingId, String status);

    long countByBookingIdIn(Collection<Long> bookingIds);
}
