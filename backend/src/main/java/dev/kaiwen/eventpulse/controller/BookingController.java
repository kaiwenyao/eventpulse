package dev.kaiwen.eventpulse.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.dto.BookingDtos.BookingView;
import dev.kaiwen.eventpulse.dto.BookingDtos.CancelRequest;
import dev.kaiwen.eventpulse.dto.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.dto.BookingDtos.PaymentIntentView;
import dev.kaiwen.eventpulse.dto.BookingDtos.RedeemRequest;
import dev.kaiwen.eventpulse.dto.BookingDtos.RedeemResult;
import dev.kaiwen.eventpulse.security.AuthUser;
import dev.kaiwen.eventpulse.service.BookingService;
import dev.kaiwen.eventpulse.service.TicketService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "bookings")
public class BookingController {

    private final BookingService bookingService;
    private final TicketService ticketService;

    public BookingController(BookingService bookingService, TicketService ticketService) {
        this.bookingService = bookingService;
        this.ticketService = ticketService;
    }

    @Operation(summary = "Create a single-tier booking under protocol A; expiresAt from the DB clock")
    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView create(@AuthenticationPrincipal AuthUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {
        return bookingService.createBooking(user.id(), idempotencyKey, request);
    }

    @GetMapping("/bookings")
    public List<BookingView> list(@AuthenticationPrincipal AuthUser user) {
        List<UUID> ids = bookingService.listBookingIds(user.id());
        return ids.stream().map(id -> bookingService.getBooking(user.id(), id)).toList();
    }

    @Operation(summary = "Booking with combined fulfilment + financial state")
    @GetMapping("/bookings/{id}")
    public BookingView detail(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return bookingService.getBooking(user.id(), id);
    }

    @Operation(summary = "Single-flight payment intent; repeat calls return the same intent")
    @PostMapping("/bookings/{id}/pay")
    public PaymentIntentView pay(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body) {
        return bookingService.payBooking(user.id(), id, idempotencyKey);
    }

    @Operation(summary = "Cancel per the purchase-time policy snapshot; refunds reserved first")
    @PostMapping("/bookings/{id}/cancel")
    public BookingView cancel(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CancelRequest request) {
        return bookingService.cancelBooking(user.id(), id, idempotencyKey, request);
    }

    @Operation(summary = "One-time reveal of ticket raw tokens (owner only, never persisted server-side)")
    @PostMapping("/bookings/{id}/tickets/reveal")
    public Map<String, Object> reveal(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        List<String> tokens = ticketService.revealTokens(user.id(), id);
        return Map.of("tokens", tokens);
    }

    @Operation(summary = "Organiser redemption; atomic single use, repeat scans return the original result")
    @PreAuthorize("hasRole('ORGANISER') or hasRole('ADMIN')")
    @PostMapping("/organiser/tickets/redeem")
    public RedeemResult redeem(@AuthenticationPrincipal AuthUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RedeemRequest request) {
        return ticketService.redeem(user.id(), request.token(), idempotencyKey);
    }
}