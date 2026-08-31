package dev.kaiwen.eventpulse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.controller.BookingController;
import dev.kaiwen.eventpulse.dto.BookingDtos;
import dev.kaiwen.eventpulse.security.AuthUser;
import dev.kaiwen.eventpulse.service.BookingService;
import dev.kaiwen.eventpulse.service.TicketService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BookingController read paths (list/detail/reveal) that no integration test
 * reaches directly; verified through mocked service interfaces.
 */
class BookingControllerTest {

    private BookingService bookingService;
    private TicketService ticketService;
    private BookingController controller;

    @BeforeEach
    void setUp() {
        bookingService = mock(BookingService.class);
        ticketService = mock(TicketService.class);
        controller = new BookingController(bookingService, ticketService);
    }

    private AuthUser user() {
        return new AuthUser(UUID.randomUUID(), "u@test.dev", "USER", 0, List.of());
    }

    private BookingDtos.BookingView view(UUID id) {
        return new BookingDtos.BookingView(id, UUID.randomUUID(), UUID.randomUUID(), "标准票", 2, "CONFIRMED",
                "ACTIVE", "NONE", 10000L, "CNY", 20000L, Map.of(), Map.of(), null, null, null, null, List.of(),
                List.of());
    }

    @Test
    void listMapsEachBookingIdToItsView() {
        AuthUser user = user();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        BookingDtos.BookingView viewA = view(a);
        BookingDtos.BookingView viewB = view(b);
        when(bookingService.listBookingIds(user.id())).thenReturn(List.of(b, a));
        when(bookingService.getBooking(user.id(), a)).thenReturn(viewA);
        when(bookingService.getBooking(user.id(), b)).thenReturn(viewB);

        List<BookingDtos.BookingView> result = controller.list(user);

        assertThat(result).containsExactly(viewB, viewA);
        verify(bookingService).getBooking(user.id(), a);
        verify(bookingService).getBooking(user.id(), b);
    }

    @Test
    void listReturnsEmptyWhenUserHasNoBookings() {
        AuthUser user = user();
        when(bookingService.listBookingIds(user.id())).thenReturn(List.of());
        assertThat(controller.list(user)).isEmpty();
    }

    @Test
    void detailDelegatesToService() {
        AuthUser user = user();
        UUID bookingId = UUID.randomUUID();
        BookingDtos.BookingView expected = view(bookingId);
        when(bookingService.getBooking(user.id(), bookingId)).thenReturn(expected);
        assertThat(controller.detail(user, bookingId)).isSameAs(expected);
    }

    @Test
    void revealReturnsOneTimeTokenList() {
        AuthUser user = user();
        UUID bookingId = UUID.randomUUID();
        List<String> tokens = List.of("tok-1", "tok-2");
        when(ticketService.revealTokens(user.id(), bookingId)).thenReturn(tokens);
        Map<String, Object> result = controller.reveal(user, bookingId);
        assertThat(result).containsEntry("tokens", tokens);
        verify(ticketService).revealTokens(user.id(), bookingId);
    }
}