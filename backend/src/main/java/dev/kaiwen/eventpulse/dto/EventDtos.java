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
            String summary,
            String description,
            String category,
            String city,
            String venueName,
            String address,
            Double latitude,
            Double longitude,
            Instant startsAt,
            Instant endsAt,
            String coverUrl,
            Instant salesStartAt,
            Instant salesEndAt,
            int maxQuantityPerBooking,
            String contactInfo,
            String attendanceNotes,
            int priceCents,
            int capacity,
            int sold,
            int remaining,
            Long organiserId,
            String status,
            String cancellationReason,
            Instant cancelledAt,
            Instant archivedAt,
            Instant updatedAt,
            Instant createdAt,
            long version,
            Boolean favourite,
            Boolean bookable,
            String unbookableReason) {
    }

    public record OrganiserEventRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 300) String summary,
            String description,
            @NotBlank @Size(max = 50) String category,
            String coverUrl,
            Long coverAssetId,
            @NotNull Instant startsAt,
            Instant endsAt,
            @NotBlank @Size(max = 50) String city,
            @Size(max = 200) String venueName,
            @Size(max = 400) String address,
            Double latitude,
            Double longitude,
            @Min(0) int priceCents,
            @Min(1) int capacity,
            Instant salesStartAt,
            Instant salesEndAt,
            @Min(1) Integer maxQuantityPerBooking,
            @Size(max = 300) String contactInfo,
            String attendanceNotes,
            Long version,
            Boolean publish) {
    }

    public record CancelEventRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record ArchiveEventRequest(@Size(max = 500) String note) {
    }
}
