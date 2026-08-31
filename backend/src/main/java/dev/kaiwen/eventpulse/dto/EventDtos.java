package dev.kaiwen.eventpulse.dto;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class EventDtos {

    private EventDtos() {
    }

    public record EventRequest(
            @NotBlank @Size(max = 200) String title,
            String description,
            @NotBlank @Size(max = 50) String category,
            @NotBlank @Size(max = 50) String city,
            @NotNull Instant startsAt,
            @Min(0) int priceCents,
            @Min(1) int capacity) {
    }

    public record EventVo(
            Long id,
            String title,
            String description,
            String category,
            String city,
            Instant startsAt,
            int priceCents,
            int capacity,
            int sold,
            int remaining,
            Long organiserId,
            String status) {
    }
}
