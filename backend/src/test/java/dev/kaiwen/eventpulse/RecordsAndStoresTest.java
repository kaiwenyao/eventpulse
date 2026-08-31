package dev.kaiwen.eventpulse;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.booking.BookingDtos;
import dev.kaiwen.eventpulse.outbox.Envelope;
import dev.kaiwen.eventpulse.outbox.OutboxRelay.RelayFailedException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coverage for small value types and exception wrappers that the integration
 * suite never exercises directly: record accessors, the outbox envelope
 * producer constant and the relay failure wrapper.
 */
class RecordsAndStoresTest {

    @Test
    void batchResultRecordAccessors() {
        BookingDtos.BatchResult result = new BookingDtos.BatchResult(7, 2);
        assertThat(result.cancelled()).isEqualTo(7);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(new BookingDtos.BatchResult(7, 2)).isEqualTo(result);
    }

    @Test
    void envelopeProducerConstantAndAccessors() {
        assertThat(Envelope.PRODUCER).isEqualTo("eventpulse-backend");
        UUID id = UUID.randomUUID();
        Envelope env = new Envelope(id, "booking.confirmed", 1, "booking", UUID.randomUUID(), 3L, null,
                null, "trace-1", Instant.now(), Envelope.PRODUCER, Map.of("k", "v"));
        assertThat(env.eventId()).isEqualTo(id);
        assertThat(env.eventType()).isEqualTo("booking.confirmed");
        assertThat(env.aggregateSequence()).isEqualTo(3L);
        assertThat(env.payload()).containsEntry("k", "v");
    }

    @Test
    void relayFailedExceptionPreservesCause() {
        IllegalStateException cause = new IllegalStateException("kafka down");
        RelayFailedException ex = new RelayFailedException("publish failed", cause);
        assertThat(ex).hasMessage("publish failed");
        assertThat(ex).hasCause(cause);
        assertThatThrownBy(() -> {
            throw ex;
        }).isInstanceOf(RelayFailedException.class);
    }
}

