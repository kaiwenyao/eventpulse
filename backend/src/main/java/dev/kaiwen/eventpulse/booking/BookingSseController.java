package dev.kaiwen.eventpulse.booking;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.kaiwen.eventpulse.auth.AuthUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.error.ApiException;
import dev.kaiwen.eventpulse.common.error.ErrorCode;

import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Minimal SSE stream for one booking's fulfilment status. Security gates per
 * the plan: Origin allowlist check, authenticated owner only (404 policy),
 * no tokens in the URL, periodic heartbeat; reconnects re-sync via REST.
 */
@RestController
public class BookingSseController {

    public record BookingStatusChanged(UUID bookingId, String status, String refundState) {
    }

    private final BookingService bookingService;
    private final AppProperties properties;
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public BookingSseController(BookingService bookingService, AppProperties properties) {
        this.bookingService = bookingService;
        this.properties = properties;
    }

    @GetMapping(value = "/api/v1/bookings/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id,
            @AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        List<String> allowed = properties.security().corsAllowedOrigins() == null
                ? List.of() : properties.security().corsAllowedOrigins();
        if (origin != null && !origin.isBlank() && !allowed.contains(origin)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "origin not allowed");
        }
        // Ownership gate: 404 for both missing and foreign bookings.
        BookingDtos.BookingView view = bookingService.getBooking(user.id(), id);

        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = emitters.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("booking-status").data(Map.of(
                    "bookingId", view.id().toString(), "status", view.status(),
                    "refundState", view.refundState())));
        }
        catch (IOException e) {
            list.remove(emitter);
        }
        return emitter;
    }

    @EventListener
    public void onStatusChanged(BookingStatusChanged event) {
        List<SseEmitter> list = emitters.get(event.bookingId());
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("booking-status").data(Map.of(
                        "bookingId", event.bookingId().toString(), "status", event.status(),
                        "refundState", event.refundState())));
            }
            catch (IOException | IllegalStateException e) {
                list.remove(emitter);
            }
        }
    }

    @Scheduled(fixedDelay = 15_000)
    public void heartbeat() {
        emitters.forEach((bookingId, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                }
                catch (IOException | IllegalStateException e) {
                    list.remove(emitter);
                }
            }
        });
    }

}
