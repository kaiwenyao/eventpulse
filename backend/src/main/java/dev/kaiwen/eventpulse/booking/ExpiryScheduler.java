package dev.kaiwen.eventpulse.booking;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expiry scheduler. Multiple copies can run safely: candidates are claimed
 * with FOR UPDATE SKIP LOCKED ordered by (expires_at, id), and each expiry is
 * a protocol-B transition that re-validates status. A low-frequency
 * compensation scan re-checks PAYMENT_PENDING rows whose deadline has passed.
 */
@Component
public class ExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScheduler.class);

    private final JdbcTemplate jdbc;
    private final BookingTransitions transitions;

    public ExpiryScheduler(JdbcTemplate jdbc, BookingTransitions transitions) {
        this.jdbc = jdbc;
        this.transitions = transitions;
    }

    @Scheduled(fixedDelayString = "${eventpulse.booking.expiry-scan-interval:PT1S}")
    public void scan() {
        List<UUID> candidates = jdbc.queryForList("""
                SELECT id FROM bookings
                WHERE status = 'PAYMENT_PENDING' AND expires_at IS NOT NULL AND expires_at <= now()
                ORDER BY expires_at, id
                LIMIT 50 FOR UPDATE SKIP LOCKED
                """, UUID.class);
        int expired = 0;
        for (UUID bookingId : candidates) {
            try {
                if (transitions.expireBooking(bookingId)) {
                    expired++;
                }
            }
            catch (Exception e) {
                log.error("expiry failed for booking {}", bookingId, e);
            }
        }
        if (expired > 0) {
            log.debug("expired {} bookings", expired);
        }
    }
}
