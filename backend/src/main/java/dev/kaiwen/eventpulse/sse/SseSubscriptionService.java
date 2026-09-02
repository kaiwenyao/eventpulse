package dev.kaiwen.eventpulse.sse;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;

/**
 * SSE 订阅入口：先做所有权检查，再把连接登记到本实例的注册表。
 * 订阅者必须已登录，且订单属于自己；主办方只能订阅自己活动的订单。
 */
@Service
@Profile("api")
public class SseSubscriptionService {

    private final SseConnectionRegistry registry;
    private final BookingRepository bookings;
    private final EventRepository events;

    public SseSubscriptionService(SseConnectionRegistry registry, BookingRepository bookings,
            EventRepository events) {
        this.registry = registry;
        this.bookings = bookings;
        this.events = events;
    }

    public SseEmitter subscribe(Long bookingId) {
        Long userId = BaseContext.getUserId();
        if (userId == null) {
            throw new BusinessException("Please sign in");
        }
        Booking booking = bookings.findById(bookingId)
                .orElseThrow(() -> BusinessException.notFound("Booking not found"));
        if (!userId.equals(booking.getUserId())) {
            Event event = booking.getEventId() == null ? null
                    : events.findById(booking.getEventId()).orElse(null);
            boolean organiserOfEvent = event != null
                    && userId.equals(event.getOrganiserId())
                    && "ORGANISER".equals(BaseContext.getRole());
            if (!organiserOfEvent) {
                throw BusinessException.forbidden("You can only subscribe to your own bookings");
            }
        }
        return registry.register(bookingId, userId);
    }
}
