package dev.kaiwen.eventpulse.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.BookingDtos.BookingVo;
import dev.kaiwen.eventpulse.dto.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.service.BookingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public Result<BookingVo> create(@Valid @RequestBody CreateBookingRequest request) {
        return Result.success(bookingService.create(request));
    }

    @GetMapping
    public Result<List<BookingVo>> list() {
        return Result.success(bookingService.listMine());
    }

    @GetMapping("/{id}")
    public Result<BookingVo> get(@PathVariable Long id) {
        return Result.success(bookingService.get(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<BookingVo> cancel(@PathVariable Long id) {
        return Result.success(bookingService.cancel(id));
    }

    @GetMapping("/{id}/tickets")
    public Result<List<dev.kaiwen.eventpulse.service.TicketService.TicketView>> tickets(@PathVariable Long id) {
        return Result.success(bookingService.tickets(id));
    }
}
