package dev.kaiwen.eventpulse.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.BookingDtos.NotificationVo;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notifications;
    private final BookingRepository bookings;

    public NotificationController(NotificationRepository notifications, BookingRepository bookings) {
        this.notifications = notifications;
        this.bookings = bookings;
    }

    @GetMapping
    public Result<List<NotificationVo>> list() {
        Long userId = BaseContext.getUserId();
        List<Long> bookingIds = bookings.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(Booking::getId)
                .toList();
        List<NotificationVo> items = bookingIds.isEmpty()
                ? List.of()
                : notifications.findByBookingIdInOrderByCreatedAtDesc(bookingIds).stream()
                        .map(n -> new NotificationVo(n.getId(), n.getBookingId(), n.getMessage(), n.getCreatedAt()))
                        .toList();
        return Result.success(items);
    }
}
