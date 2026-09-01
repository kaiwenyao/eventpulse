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
            Instant createdAt,
            Instant cancelledAt,
            String organiserNote,
            long checkedInCount,
            long validCount) {
    }

    public record NotificationVo(
            Long id,
            Long userId,
            Long eventId,
            Long bookingId,
            String type,
            String title,
            String message,
            String payload,
            Instant readAt,
            Instant createdAt) {
    }

    public record CheckInRequest(String code, String source) {
    }

    public record UndoCheckInRequest(String reason) {
    }
}
