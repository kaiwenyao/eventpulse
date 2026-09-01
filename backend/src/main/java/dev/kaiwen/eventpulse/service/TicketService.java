package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.domain.TicketStatus;
import dev.kaiwen.eventpulse.entity.Ticket;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.TicketRepository;

@Service
public class TicketService {

    private final TicketRepository tickets;
    private final OrganiserEventService organiserEvents;
    private final AppProperties properties;

    public TicketService(TicketRepository tickets, OrganiserEventService organiserEvents, AppProperties properties) {
        this.tickets = tickets;
        this.organiserEvents = organiserEvents;
        this.properties = properties;
    }

    public List<Ticket> issue(Long bookingId, Long eventId, int quantity) {
        List<Ticket> issued = new ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            String raw = TicketCodes.raw();
            Ticket ticket = new Ticket();
            ticket.setBookingId(bookingId);
            ticket.setEventId(eventId);
            ticket.setTicketCodeHash(TicketCodes.hash(raw));
            ticket.setTicketCodeCipher(TicketCodes.encrypt(raw, properties.getSecretKey()));
            ticket.setStatus(TicketStatus.VALID);
            ticket.setCreatedAt(Instant.now());
            tickets.save(ticket);
            issued.add(ticket);
        }
        return issued;
    }

    public String reveal(Ticket ticket) {
        return TicketCodes.decrypt(ticket.getTicketCodeCipher(), properties.getSecretKey());
    }

    public List<Ticket> forBooking(Long bookingId) {
        return tickets.findByBookingIdOrderByIdAsc(bookingId);
    }

    public Ticket lookup(String code) {
        EventService.requireOrganiser();
        Ticket ticket = tickets.findByTicketCodeHash(TicketCodes.hash(code))
                .orElseThrow(() -> BusinessException.notFound("票据不存在"));
        organiserEvents.requireOwn(ticket.getEventId());
        return ticket;
    }

    @Transactional
    public Ticket checkIn(String code, String source) {
        Ticket ticket = lookup(code);
        if (TicketStatus.CHECKED_IN.equals(ticket.getStatus())) {
            throw BusinessException.conflict("该票已于 " + ticket.getCheckedInAt() + " 核销");
        }
        if (!TicketStatus.VALID.equals(ticket.getStatus())) {
            throw BusinessException.conflict("票据已失效，无法核销");
        }
        ticket.setStatus(TicketStatus.CHECKED_IN);
        ticket.setCheckedInAt(Instant.now());
        ticket.setCheckedInBy(BaseContext.getUserId());
        ticket.setCheckInSource(source == null || source.isBlank() ? "manual" : source);
        return ticket;
    }

    @Transactional
    public Ticket undoCheckIn(Long ticketId, String reason) {
        EventService.requireOrganiser();
        Ticket ticket = tickets.findById(ticketId).orElseThrow(() -> BusinessException.notFound("票据不存在"));
        organiserEvents.requireOwn(ticket.getEventId());
        if (!TicketStatus.CHECKED_IN.equals(ticket.getStatus())) {
            throw BusinessException.conflict("只有已核销的票可以撤销");
        }
        ticket.setStatus(TicketStatus.VALID);
        ticket.setRevokedAt(Instant.now());
        ticket.setRevokedBy(BaseContext.getUserId());
        ticket.setRevocationReason(reason);
        ticket.setCheckedInAt(null);
        ticket.setCheckedInBy(null);
        ticket.setCheckInSource(null);
        return ticket;
    }

    public void cancelForBooking(Long bookingId) {
        tickets.findByBookingIdOrderByIdAsc(bookingId).forEach(ticket -> {
            if (TicketStatus.VALID.equals(ticket.getStatus()) || TicketStatus.CHECKED_IN.equals(ticket.getStatus())) {
                ticket.setStatus(TicketStatus.CANCELLED);
            }
        });
    }

    public record TicketView(Long id, Long bookingId, Long eventId, String code, String status,
            Instant checkedInAt, Instant createdAt) {
    }

    public TicketView toView(Ticket ticket, boolean revealCode) {
        return new TicketView(
                ticket.getId(),
                ticket.getBookingId(),
                ticket.getEventId(),
                revealCode ? reveal(ticket) : null,
                ticket.getStatus(),
                ticket.getCheckedInAt(),
                ticket.getCreatedAt());
    }
}
