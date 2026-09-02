package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.domain.TicketStatus;
import dev.kaiwen.eventpulse.dto.BookingDtos.BookingVo;
import dev.kaiwen.eventpulse.dto.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.outbox.KafkaTopics;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.TicketRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;

@Service
public class BookingService {

    private final BookingRepository bookings;
    private final EventService eventService;
    private final EventRepository events;
    private final TicketService ticketService;
    private final TicketRepository tickets;
    private final UserRepository users;
    private final OutboxWriter outbox;
    private final PopularCache popularCache;

    public BookingService(
            BookingRepository bookings,
            EventService eventService,
            EventRepository events,
            TicketService ticketService,
            TicketRepository tickets,
            UserRepository users,
            OutboxWriter outbox,
            PopularCache popularCache) {
        this.bookings = bookings;
        this.eventService = eventService;
        this.events = events;
        this.ticketService = ticketService;
        this.tickets = tickets;
        this.users = users;
        this.outbox = outbox;
        this.popularCache = popularCache;
    }

    @Transactional
    public BookingVo create(CreateBookingRequest request) {
        Long userId = requireLogin();
        Event event = eventService.require(request.eventId());
        String reason = EventService.unbookableReason(event, Instant.now());
        if (reason != null) {
            throw "Sold out".equals(reason) ? BusinessException.conflict(reason) : new BusinessException(reason);
        }
        int maxQty = event.getMaxQuantityPerBooking() <= 0 ? 10 : event.getMaxQuantityPerBooking();
        if (request.quantity() > maxQty) {
            throw new BusinessException("Maximum " + maxQty + " tickets per booking");
        }
        long paidCents = Math.multiplyExact((long) event.getPriceCents(), request.quantity());
        // Keep the activity-before-wallet order used by both cancellation flows to avoid deadlocks.
        int updated = events.incrementSold(event.getId(), request.quantity());
        if (updated == 0) {
            Event latest = eventService.require(request.eventId());
            String latestReason = EventService.unbookableReason(latest, Instant.now());
            throw BusinessException.conflict(latestReason == null ? "Sold out" : latestReason);
        }
        if (users.debitWalletIfEnough(userId, paidCents) == 0) {
            throw BusinessException.conflict("Insufficient wallet balance");
        }

        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setEventId(event.getId());
        booking.setQuantity(request.quantity());
        booking.setPaidCents(paidCents);
        booking.setStatus("CONFIRMED");
        booking.setCreatedAt(Instant.now());
        bookings.save(booking);
        ticketService.issue(booking.getId(), event.getId(), request.quantity());
        outbox.write(KafkaTopics.BOOKING_EVENTS, "BOOKING_CREATED", "booking:" + booking.getId(),
                "BOOKING_CREATED:" + booking.getId(),
                Map.of(
                        "type", "BOOKING_CREATED",
                        "userId", userId,
                        "eventId", event.getId(),
                        "bookingId", booking.getId(),
                        "quantity", request.quantity(),
                        "title", "Booking confirmed",
                        "message", "You booked " + request.quantity() + " ticket(s) for \"" + event.getTitle() + "\""));
        popularCache.evict();
        return toVo(booking, event.getTitle());
    }

    public List<BookingVo> listMine() {
        Long userId = requireLogin();
        return bookings.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toVo)
                .toList();
    }

    public BookingVo get(Long id) {
        return toVo(requireOwn(id));
    }

    public List<TicketService.TicketView> tickets(Long id) {
        requireOwn(id);
        return ticketService.forBooking(id).stream()
                .map(ticket -> ticketService.toView(ticket, true))
                .toList();
    }

    @Transactional
    public BookingVo cancel(Long id) {
        Booking booking = requireOwn(id);
        Event event = eventService.require(booking.getEventId());
        // Keep the activity -> ticket -> booking -> wallet order used when an organiser cancels an event.
        if (events.decrementSoldForCustomerCancellation(event.getId(), booking.getQuantity()) == 0) {
            throw BusinessException.conflict("Event has started or cannot be cancelled in its current state");
        }
        List<dev.kaiwen.eventpulse.entity.Ticket> lockedTickets = ticketService.lockForBooking(booking.getId());
        if (lockedTickets.stream().anyMatch(ticket -> TicketStatus.CHECKED_IN.equals(ticket.getStatus()))) {
            throw BusinessException.conflict("A ticket has already been checked in, refund is not allowed");
        }
        if (bookings.cancelConfirmed(booking.getId()) == 0) {
            throw new BusinessException("Booking already cancelled");
        }
        booking.setStatus("CANCELLED");
        booking.setCancelledAt(Instant.now());
        users.creditWallet(booking.getUserId(), booking.getPaidCents());
        ticketService.cancelLocked(lockedTickets);
        outbox.write(KafkaTopics.BOOKING_EVENTS, "BOOKING_CANCELLED", "booking:" + booking.getId(),
                "BOOKING_CANCELLED:" + booking.getId(),
                Map.of(
                        "type", "BOOKING_CANCELLED",
                        "userId", booking.getUserId(),
                        "eventId", event.getId(),
                        "bookingId", booking.getId(),
                        "quantity", booking.getQuantity(),
                        "title", "Booking cancelled",
                        "message", "You cancelled your booking for \"" + event.getTitle() + "\""));
        popularCache.evict();
        return toVo(booking, event.getTitle());
    }

    private Booking requireOwn(Long id) {
        Long userId = requireLogin();
        Booking booking = bookings.findById(id).orElseThrow(() -> BusinessException.notFound("Booking not found"));
        if (!booking.getUserId().equals(userId)) {
            throw BusinessException.forbidden("You can only view your own bookings");
        }
        return booking;
    }

    private static Long requireLogin() {
        Long userId = BaseContext.getUserId();
        if (userId == null) {
            throw new BusinessException("Please sign in");
        }
        return userId;
    }

    public BookingVo toPublic(Booking booking) {
        return toVo(booking);
    }

    private BookingVo toVo(Booking booking) {
        String title = eventService.require(booking.getEventId()).getTitle();
        return toVo(booking, title);
    }

    private BookingVo toVo(Booking booking, String eventTitle) {
        long checkedIn = tickets.countByBookingIdAndStatus(booking.getId(), TicketStatus.CHECKED_IN);
        long valid = tickets.countByBookingIdAndStatus(booking.getId(), TicketStatus.VALID);
        return new BookingVo(
                booking.getId(),
                booking.getEventId(),
                eventTitle,
                booking.getQuantity(),
                booking.getStatus(),
                booking.getCreatedAt(),
                booking.getCancelledAt(),
                booking.getOrganiserNote(),
                checkedIn,
                valid);
    }
}
