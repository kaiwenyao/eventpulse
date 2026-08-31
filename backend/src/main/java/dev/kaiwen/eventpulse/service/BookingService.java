package dev.kaiwen.eventpulse.service;

import java.util.List;
import java.util.UUID;

import dev.kaiwen.eventpulse.dto.BookingDtos.BookingView;
import dev.kaiwen.eventpulse.dto.BookingDtos.CancelRequest;
import dev.kaiwen.eventpulse.dto.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.dto.BookingDtos.PaymentIntentView;

/**
 * Booking and payment-intent business surface. Every state transition re-locks
 * rows in the fixed protocol-B order (booking, quota, inventory, reservation,
 * tickets, payment balance) inside the implementation.
 */
public interface BookingService {

    BookingView createBooking(UUID userId, String rawIdempotencyKey, CreateBookingRequest request);

    /** Ids of the user's bookings, newest first (views are assembled per id). */
    List<UUID> listBookingIds(UUID userId);

    BookingView getBooking(UUID userId, UUID bookingId);

    /** Single-flight payment intent; repeat calls return the same intent. */
    PaymentIntentView payBooking(UUID userId, UUID bookingId, String rawIdempotencyKey);

    /** Cancel per the purchase-time policy snapshot; refunds reserved first. */
    BookingView cancelBooking(UUID userId, UUID bookingId, String rawIdempotencyKey, CancelRequest request);
}