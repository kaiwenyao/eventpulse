package dev.kaiwen.eventpulse.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-memory reveal store is the Redis-down fallback, exercised here in
 * isolation (the store lives nested inside TicketServiceImpl). Semantics must
 * mirror the Redis path: entries hold encrypted staging values, they expire,
 * they are size-bounded, and reads are NON-destructive — a patron can re-open
 * the order page and re-reveal the same tokens until the TTL (H1 regression
 * guard).
 */
class InMemoryRevealStoreTest {

    private static final Instant FAR_FUTURE = Instant.now().plusSeconds(3600);
    private static final Instant EXPIRED = Instant.now().minusSeconds(1);

    @Test
    void snapshotIsNonDestructiveAndKeepsEveryEntry() {
        UUID bookingA = UUID.randomUUID();
        UUID bookingB = UUID.randomUUID();
        // Callers pass the AES-GCM staging values; the store is cipher-agnostic.
        TicketServiceImpl.InMemoryRevealStore.addAll(bookingA, List.of("v1:enc-1", "v1:enc-2"), FAR_FUTURE);
        TicketServiceImpl.InMemoryRevealStore.addAll(bookingB, List.of("v1:enc-b"), FAR_FUTURE);

        assertThat(TicketServiceImpl.InMemoryRevealStore.snapshot(bookingA))
                .containsExactly("v1:enc-1", "v1:enc-2");
        // Repeat read: same entries, never consumed.
        assertThat(TicketServiceImpl.InMemoryRevealStore.snapshot(bookingA))
                .containsExactly("v1:enc-1", "v1:enc-2");
        assertThat(TicketServiceImpl.InMemoryRevealStore.snapshot(bookingB)).containsExactly("v1:enc-b");
        assertThat(TicketServiceImpl.InMemoryRevealStore.snapshot(UUID.randomUUID())).isEmpty();
    }

    @Test
    void expiredEntriesAreNotServed() {
        UUID booking = UUID.randomUUID();
        TicketServiceImpl.InMemoryRevealStore.addAll(booking, List.of("v1:enc-stale"), EXPIRED);
        assertThat(TicketServiceImpl.InMemoryRevealStore.snapshot(booking)).isEmpty();
        // A later entry with a fresh expiry is served again.
        TicketServiceImpl.InMemoryRevealStore.addAll(booking, List.of("v1:enc-fresh"), FAR_FUTURE);
        assertThat(TicketServiceImpl.InMemoryRevealStore.snapshot(booking)).containsExactly("v1:enc-fresh");
    }

    @Test
    void theStoreIsSizeBounded() {
        for (int i = 0; i < 2500; i++) {
            TicketServiceImpl.InMemoryRevealStore.addAll(UUID.randomUUID(), List.of("v1:enc-" + i),
                    FAR_FUTURE);
        }
        // The bound (2048) must hold: nothing beyond it is retained.
        assertThat(TicketServiceImpl.InMemoryRevealStore.snapshot(UUID.randomUUID())).isEmpty();
    }
}