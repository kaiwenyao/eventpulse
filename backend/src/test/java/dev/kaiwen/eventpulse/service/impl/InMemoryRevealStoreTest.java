package dev.kaiwen.eventpulse.service.impl;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-memory reveal-token store is the Redis-down fallback exercised here in
 * isolation; the package-private accessors are only reachable from this package
 * (the store lives nested inside TicketServiceImpl).
 */
class InMemoryRevealStoreTest {

    @Test
    void aggregatesTokensPerBookingAndDefaultsUnknownToEmpty() {
        UUID bookingA = UUID.randomUUID();
        UUID bookingB = UUID.randomUUID();
        TicketServiceImpl.InMemoryRevealStore.addAll(bookingA, List.of("ta-1", "ta-2"));
        TicketServiceImpl.InMemoryRevealStore.addAll(bookingA, List.of("ta-3"));
        TicketServiceImpl.InMemoryRevealStore.addAll(bookingB, List.of("tb-1"));

        assertThat(TicketServiceImpl.InMemoryRevealStore.get(bookingA))
                .containsExactly("ta-1", "ta-2", "ta-3");
        assertThat(TicketServiceImpl.InMemoryRevealStore.get(bookingB)).containsExactly("tb-1");
        assertThat(TicketServiceImpl.InMemoryRevealStore.get(UUID.randomUUID())).isEmpty();
    }
}
