package dev.kaiwen.eventpulse.service.impl;

import java.util.UUID;

import dev.kaiwen.eventpulse.batch.BookingCancellationBatch;
import dev.kaiwen.eventpulse.security.AuthUser;
import dev.kaiwen.eventpulse.service.EventCancellationService;
import dev.kaiwen.eventpulse.service.OrganiserCatalogueService;

import org.springframework.stereotype.Service;

/**
 * Keeps the event update and order batch as two transaction phases: the
 * proxied catalogue call commits before the batch starts locking bookings.
 */
@Service
public class EventCancellationServiceImpl implements EventCancellationService {

    private final OrganiserCatalogueService organiserCatalogueService;
    private final BookingCancellationBatch cancellationBatch;

    public EventCancellationServiceImpl(OrganiserCatalogueService organiserCatalogueService,
            BookingCancellationBatch cancellationBatch) {
        this.organiserCatalogueService = organiserCatalogueService;
        this.cancellationBatch = cancellationBatch;
    }

    @Override
    public CancellationResult cancelEvent(AuthUser user, UUID eventId, String reason) {
        organiserCatalogueService.cancelEvent(user, eventId, reason);
        BookingCancellationBatch.BatchResult result = cancellationBatch.runForEvent(eventId);
        return new CancellationResult(result.cancelled(), result.failed());
    }
}
