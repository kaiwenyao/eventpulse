package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.dto.BookingDtos.BookingVo;
import dev.kaiwen.eventpulse.dto.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.kafka.BookingProducer;
import dev.kaiwen.eventpulse.repository.BookingRepository;

@Service
public class BookingService {

    private final BookingRepository bookings;
    private final EventService eventService;
    private final BookingProducer producer;

    public BookingService(BookingRepository bookings, EventService eventService, BookingProducer producer) {
        this.bookings = bookings;
        this.eventService = eventService;
        this.producer = producer;
    }

    @Transactional
    public BookingVo create(CreateBookingRequest request) {
        Long userId = requireLogin();
        Event event = eventService.require(request.eventId());
        if (!"PUBLISHED".equals(event.getStatus())) {
            throw new BusinessException("活动已取消，无法预订");
        }
        if (event.getSold() + request.quantity() > event.getCapacity()) {
            throw new BusinessException("余票不足");
        }
        event.setSold(event.getSold() + request.quantity());

        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setEventId(event.getId());
        booking.setQuantity(request.quantity());
        booking.setStatus("CONFIRMED");
        booking.setCreatedAt(Instant.now());
        bookings.save(booking);

        producer.sendCreated(booking);
        return toVo(booking, event.getTitle());
    }

    public List<BookingVo> listMine() {
        Long userId = requireLogin();
        return bookings.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toVo)
                .toList();
    }

    public BookingVo get(Long id) {
        return toVo(requireOwn(id));
    }

    @Transactional
    public BookingVo cancel(Long id) {
        Booking booking = requireOwn(id);
        if (!"CONFIRMED".equals(booking.getStatus())) {
            throw new BusinessException("订单已取消");
        }
        Event event = eventService.require(booking.getEventId());
        event.setSold(Math.max(0, event.getSold() - booking.getQuantity()));
        booking.setStatus("CANCELLED");
        producer.sendCancelled(booking);
        return toVo(booking, event.getTitle());
    }

    private Booking requireOwn(Long id) {
        Long userId = requireLogin();
        Booking booking = bookings.findById(id).orElseThrow(() -> new BusinessException("订单不存在"));
        if (!booking.getUserId().equals(userId)) {
            throw new BusinessException("只能查看自己的订单");
        }
        return booking;
    }

    private static Long requireLogin() {
        Long userId = BaseContext.getUserId();
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        return userId;
    }

    private BookingVo toVo(Booking booking) {
        String title = eventService.require(booking.getEventId()).getTitle();
        return toVo(booking, title);
    }

    private static BookingVo toVo(Booking booking, String eventTitle) {
        return new BookingVo(
                booking.getId(),
                booking.getEventId(),
                eventTitle,
                booking.getQuantity(),
                booking.getStatus(),
                booking.getCreatedAt());
    }
}
