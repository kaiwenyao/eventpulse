package dev.kaiwen.eventpulse.dto;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record CreateBookingRequest(
            @NotNull Long eventId,
            @Min(1) @Max(10) int quantity) {
    }

    public record BookingVo(
            Long id,
            Long eventId,
            String eventTitle,
            int quantity,
            String status,
            Instant createdAt) {
    }

    public record NotificationVo(Long id, Long bookingId, String message, Instant createdAt) {
    }
}
