package dev.kaiwen.eventpulse.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.kaiwen.eventpulse.entity.Ticket;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByBookingIdOrderByIdAsc(Long bookingId);

    List<Ticket> findByEventIdOrderByIdAsc(Long eventId);

    Optional<Ticket> findByTicketCodeHash(String ticketCodeHash);

    long countByEventIdAndStatus(Long eventId, String status);

    long countByBookingIdAndStatus(Long bookingId, String status);

    long countByBookingIdIn(Collection<Long> bookingIds);
}
