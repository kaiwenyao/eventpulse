package dev.kaiwen.eventpulse.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.kaiwen.eventpulse.repository.EventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class EventLifecycleWorkerTest {

    @Mock
    EventRepository events;

    @Test
    void conditionalUpdatesRunInAllowedDirectionAndRecordCounts() {
        Instant before = Instant.now();
        when(events.startPublishedEvents(eq("PUBLISHED"), eq("ONGOING"), any())).thenReturn(2);
        when(events.finishOngoingEvents(eq("ONGOING"), eq("FINISHED"), any())).thenReturn(1);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EventLifecycleWorker worker = new EventLifecycleWorker(events, meters);

        worker.advance();

        verify(events).startPublishedEvents(eq("PUBLISHED"), eq("ONGOING"), any());
        verify(events).finishOngoingEvents(eq("ONGOING"), eq("FINISHED"), any());
        assertThat(meters.get("eventpulse.lifecycle.started").counter().count()).isEqualTo(2);
        assertThat(meters.get("eventpulse.lifecycle.finished").counter().count()).isEqualTo(1);
        Instant after = Instant.now();
        assertThat(before).isBefore(after);
    }

    @Test
    void failuresAreCountedAndRethrown() {
        when(events.startPublishedEvents(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EventLifecycleWorker worker = new EventLifecycleWorker(events, meters);

        assertThatThrownBy(worker::advance).isInstanceOf(IllegalStateException.class);
        assertThat(meters.get("eventpulse.lifecycle.failures").counter().count()).isEqualTo(1);
        assertThat(meters.get("eventpulse.lifecycle.scan").timers()).isNotEmpty();
    }
}
