package com.eventpulse;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eventpulse.auth.AuthUser;
import com.eventpulse.booking.BookingDtos;
import com.eventpulse.booking.BookingSseController;
import com.eventpulse.booking.BookingService;
import com.eventpulse.common.AppProperties;
import com.eventpulse.common.error.ApiException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SSE controller gates: origin allowlist, hidden-object ownership, events. */
class BookingSseControllerTest {

    private BookingService bookingService;
    private BookingSseController controller;
    private HttpServletRequest request;
    private UUID bookingId;

    @BeforeEach
    void setup() {
        bookingService = mock(BookingService.class);
        AppProperties properties = new AppProperties(
                new AppProperties.Security("s", "p", Duration.ofMinutes(1), Duration.ofDays(1),
                        Duration.ofMinutes(10), List.of("http://localhost:3000")),
                null, null, null, null, null, null);
        controller = new BookingSseController(bookingService, properties);
        request = mock(HttpServletRequest.class);
        bookingId = UUID.randomUUID();
        when(request.getHeader("Origin")).thenReturn("http://localhost:3000");
        when(bookingService.getBooking(any(UUID.class), any(UUID.class))).thenReturn(
                new BookingDtos.BookingView(bookingId, UUID.randomUUID(), UUID.randomUUID(), "标准票", 2,
                        "PAYMENT_PENDING", "ACTIVE", "NONE", 10000L, "CNY", 20000L, Map.of(), Map.of(),
                        null, null, null, null, List.of(), List.of()));
    }

    private AuthUser user() {
        return new AuthUser(UUID.randomUUID(), "u@test.dev", "USER", 0, List.of());
    }

    @Test
    void subscribeSendsInitialStatusEvent() {
        SseEmitter emitter = controller.events(bookingId, user(), request);
        assertThat(emitter).isNotNull();
    }

    @Test
    void foreignOriginIsRejected() {
        when(request.getHeader("Origin")).thenReturn("http://evil.example");
        assertThatThrownBy(() -> controller.events(bookingId, user(), request))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(com.eventpulse.common.error.ErrorCode.FORBIDDEN));
    }

    @Test
    void missingOriginIsAllowedWhileBlankRejected() {
        when(request.getHeader("Origin")).thenReturn(null);
        assertThat(controller.events(bookingId, user(), request)).isNotNull();
    }

    @Test
    void ownershipFailurePropagatesAsHidden404() {
        when(bookingService.getBooking(any(UUID.class), any(UUID.class))).thenThrow(ApiException.notFound());
        assertThatThrownBy(() -> controller.events(bookingId, user(), request))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(com.eventpulse.common.error.ErrorCode.NOT_FOUND));
    }

    @Test
    void statusChangeEventAndHeartbeatDoNotThrow() {
        controller.events(bookingId, user(), request);
        controller.onStatusChanged(new BookingSseController.BookingStatusChanged(bookingId, "CONFIRMED",
                "NONE"));
        // unknown booking id: no emitters -> no-op
        controller.onStatusChanged(new BookingSseController.BookingStatusChanged(UUID.randomUUID(),
                "EXPIRED", "NONE"));
        controller.heartbeat();
    }
}
