package dev.kaiwen.eventpulse;

import java.util.List;
import java.util.UUID;

import dev.kaiwen.eventpulse.batch.BookingCancellationBatch;
import dev.kaiwen.eventpulse.security.AuthUser;
import dev.kaiwen.eventpulse.service.EventCancellationService.CancellationResult;
import dev.kaiwen.eventpulse.service.OrganiserCatalogueService;
import dev.kaiwen.eventpulse.service.impl.EventCancellationServiceImpl;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventCancellationServiceTest {

    @Test
    void commitsEventCancellationBeforeRunningBookingBatch() {
        OrganiserCatalogueService catalogue = mock(OrganiserCatalogueService.class);
        BookingCancellationBatch batch = mock(BookingCancellationBatch.class);
        EventCancellationServiceImpl service = new EventCancellationServiceImpl(catalogue, batch);
        AuthUser user = new AuthUser(UUID.randomUUID(), "organiser@test.dev", "ORGANISER", 0, List.of());
        UUID eventId = UUID.randomUUID();
        when(batch.runForEvent(eventId)).thenReturn(new BookingCancellationBatch.BatchResult(3, 1));

        CancellationResult result = service.cancelEvent(user, eventId, "weather");

        InOrder ordered = inOrder(catalogue, batch);
        ordered.verify(catalogue).cancelEvent(user, eventId, "weather");
        ordered.verify(batch).runForEvent(eventId);
        assertThat(result).isEqualTo(new CancellationResult(3, 1));
    }
}
