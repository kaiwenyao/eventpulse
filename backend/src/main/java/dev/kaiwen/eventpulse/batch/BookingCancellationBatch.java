package dev.kaiwen.eventpulse.batch;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.kaiwen.eventpulse.service.BookingTransitions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Event-cancellation batch. Each booking is cancelled independently under
 * protocol B with a stable id cursor; the batch is idempotent and can be
 * re-run (admin entry point) after partial failure. It never holds an event
 * row lock while waiting on a booking lock, so no lock-cycle back edge exists.
 */
@Component
public class BookingCancellationBatch {

    private static final Logger log = LoggerFactory.getLogger(BookingCancellationBatch.class);

    private final JdbcTemplate jdbc;
    private final BookingTransitions transitions;

    public BookingCancellationBatch(JdbcTemplate jdbc, BookingTransitions transitions) {
        this.jdbc = jdbc;
        this.transitions = transitions;
    }

    public record BatchResult(int cancelled, int failed) {
    }

    public BatchResult runForEvent(UUID eventId) {
        int cancelled = 0;
        int failed = 0;
        UUID cursor = null;
        while (true) {
            List<UUID> page = cursor == null
                    ? jdbc.queryForList("""
                            SELECT id FROM bookings WHERE event_id = ?
                              AND status IN ('PAYMENT_PENDING', 'CONFIRMED')
                            ORDER BY id LIMIT 100
                            """, UUID.class, eventId)
                    : jdbc.queryForList("""
                            SELECT id FROM bookings WHERE event_id = ? AND id > ?
                              AND status IN ('PAYMENT_PENDING', 'CONFIRMED')
                            ORDER BY id LIMIT 100
                            """, UUID.class, eventId, cursor);
            if (page.isEmpty()) {
                break;
            }
            for (UUID bookingId : page) {
                try {
                    if (transitions.cancel(null, bookingId, true, "system:event-cancel")) {
                        cancelled++;
                    }
                }
                catch (Exception e) {
                    failed++;
                    log.warn("cancellation failed for booking {} on event {}: {}", bookingId, eventId,
                            e.getMessage());
                }
                cursor = bookingId;
            }
        }
        log.info("event {} cancellation batch: {} cancelled, {} failed", eventId, cancelled, failed);
        return new BatchResult(cancelled, failed);
    }
}
