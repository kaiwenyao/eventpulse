package dev.kaiwen.eventpulse.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.BookingDtos.BookingVo;
import dev.kaiwen.eventpulse.dto.BookingDtos.CheckInRequest;
import dev.kaiwen.eventpulse.dto.BookingDtos.UndoCheckInRequest;
import dev.kaiwen.eventpulse.entity.Ticket;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;
import dev.kaiwen.eventpulse.service.BookingService;
import dev.kaiwen.eventpulse.service.OrganiserEventService;
import dev.kaiwen.eventpulse.service.TicketService;

@RestController
@Profile("api")
@RequestMapping("/api/organiser")
public class OrganiserOpsController {

    private final OrganiserEventService organiserEvents;
    private final TicketService tickets;
    private final BookingRepository bookings;
    private final UserRepository users;
    private final BookingService bookingService;

    public OrganiserOpsController(
            OrganiserEventService organiserEvents,
            TicketService tickets,
            BookingRepository bookings,
            UserRepository users,
            BookingService bookingService) {
        this.organiserEvents = organiserEvents;
        this.tickets = tickets;
        this.bookings = bookings;
        this.users = users;
        this.bookingService = bookingService;
    }

    @GetMapping("/events/{id}/bookings")
    public Result<List<BookingVo>> eventBookings(@PathVariable Long id) {
        organiserEvents.requireOwn(id);
        List<BookingVo> items = bookings.findByEventIdOrderByCreatedAtDesc(id).stream()
                .map(bookingService::toPublic)
                .toList();
        return Result.success(items);
    }

    @GetMapping("/events/{id}/attendees")
    public Result<List<Map<String, Object>>> attendees(@PathVariable Long id) {
        organiserEvents.requireOwn(id);
        return Result.success(attendeeRows(id));
    }

    @GetMapping("/events/{id}/attendees.csv")
    public ResponseEntity<byte[]> attendeesCsv(@PathVariable Long id) {
        organiserEvents.requireOwn(id);
        StringBuilder csv = new StringBuilder("bookingId,ticketId,name,email,status,checkedInAt\n");
        for (Map<String, Object> row : attendeeRows(id)) {
            csv.append(row.get("bookingId")).append(',')
                    .append(row.get("ticketId")).append(',')
                    .append(row.get("name")).append(',')
                    .append(row.get("email")).append(',')
                    .append(row.get("status")).append(',')
                    .append(row.get("checkedInAt")).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=attendees.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/tickets/check-in")
    public Result<TicketService.TicketView> checkIn(@RequestBody CheckInRequest request) {
        Ticket ticket = tickets.checkIn(request.code(), request.source());
        return Result.success(tickets.toView(ticket, false));
    }

    @PostMapping("/tickets/{id}/undo-check-in")
    public Result<TicketService.TicketView> undo(
            @PathVariable Long id,
            @RequestBody(required = false) UndoCheckInRequest request) {
        Ticket ticket = tickets.undoCheckIn(id, request == null ? null : request.reason());
        return Result.success(tickets.toView(ticket, false));
    }

    @GetMapping("/tickets/{code}")
    public Result<TicketService.TicketView> lookup(@PathVariable String code) {
        return Result.success(tickets.toView(tickets.lookup(code), false));
    }

    private List<Map<String, Object>> attendeeRows(Long eventId) {
        return bookings.findByEventIdOrderByCreatedAtDesc(eventId).stream()
                .flatMap(booking -> {
                    User user = users.findById(booking.getUserId()).orElse(null);
                    return tickets.forBooking(booking.getId()).stream().map(ticket -> Map.<String, Object>of(
                            "bookingId", booking.getId(),
                            "ticketId", ticket.getId(),
                            "name", user == null ? "" : user.getName(),
                            "email", user == null ? "" : user.getEmail(),
                            "status", ticket.getStatus(),
                            "checkedInAt", String.valueOf(ticket.getCheckedInAt())));
                })
                .toList();
    }
}
