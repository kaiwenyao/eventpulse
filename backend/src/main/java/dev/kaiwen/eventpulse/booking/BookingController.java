package dev.kaiwen.eventpulse.booking;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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

import dev.kaiwen.eventpulse.auth.AuthUser;
import dev.kaiwen.eventpulse.booking.BookingDtos.BookingView;
import dev.kaiwen.eventpulse.booking.BookingDtos.CancelRequest;
import dev.kaiwen.eventpulse.booking.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.booking.BookingDtos.PaymentIntentView;
import dev.kaiwen.eventpulse.booking.BookingDtos.RedeemRequest;
import dev.kaiwen.eventpulse.booking.BookingDtos.RedeemResult;
import dev.kaiwen.eventpulse.common.error.ApiException;
import dev.kaiwen.eventpulse.common.error.ErrorCode;
import dev.kaiwen.eventpulse.ticketing.TicketService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "bookings")
public class BookingController {

    private final BookingService bookingService;
    private final BookingTransitions transitions;
    private final IdempotencyService idempotency;
    private final TicketService ticketService;
    private final JdbcTemplate jdbc;

    public BookingController(BookingService bookingService, BookingTransitions transitions,
            IdempotencyService idempotency, TicketService ticketService, JdbcTemplate jdbc) {
        this.bookingService = bookingService;
        this.transitions = transitions;
        this.idempotency = idempotency;
        this.ticketService = ticketService;
        this.jdbc = jdbc;
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
        List<UUID> ids = jdbc.queryForList("SELECT id FROM bookings WHERE user_id = ? ORDER BY created_at DESC",
                UUID.class, user.id());
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
        IdempotencyService.Fingerprint fingerprint = idempotency.claim(user.id(), "bookings:pay",
                idempotencyKey == null ? "" : idempotencyKey, Map.of("bookingId", id.toString()));
        requireOwner(user.id(), id);
        PaymentIntentView intent = transitions.createPaymentIntent(id);
        idempotency.complete(fingerprint, 200, intent);
        return intent;
    }

    @Operation(summary = "Cancel per the purchase-time policy snapshot; refunds reserved first")
    @PostMapping("/bookings/{id}/cancel")
    public BookingView cancel(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CancelRequest request) {
        IdempotencyService.Fingerprint fingerprint = idempotency.claim(user.id(), "bookings:cancel",
                idempotencyKey == null ? "" : idempotencyKey, Map.of("bookingId", id.toString()));
        requireOwner(user.id(), id);
        transitions.cancel(user.id(), id, false, "user");
        BookingView view = bookingService.getBooking(user.id(), id);
        idempotency.complete(fingerprint, 200, view);
        return view;
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

    private void requireOwner(UUID userId, UUID bookingId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE id = ? AND user_id = ?",
                Integer.class, bookingId, userId);
        if (count == null || count == 0) {
            throw ApiException.notFound();
        }
    }
}
