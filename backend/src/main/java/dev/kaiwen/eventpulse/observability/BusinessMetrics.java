package dev.kaiwen.eventpulse.observability;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Business-facing metrics exposed by Actuator/Prometheus. JVM and datasource
 * meters alone cannot show whether the durable workflows are healthy, so the
 * gauges are refreshed from the database on the batch pool and the ticket
 * counters are recorded at the business boundary.
 */
@Component
public class BusinessMetrics {

    private final JdbcTemplate jdbc;
    private final AtomicLong outboxOldestPendingSeconds = new AtomicLong();
    private final AtomicLong consumerLag = new AtomicLong();
    private final AtomicLong commandLeaseAgeSeconds = new AtomicLong();
    private final AtomicLong manualReviewCommands = new AtomicLong();
    private final AtomicLong unknownCommands = new AtomicLong();
    private final AtomicLong inventoryEquationViolations = new AtomicLong();
    private final Counter redeemAttempts;
    private final Counter redeemRejections;

    public BusinessMetrics(MeterRegistry registry,
            @Qualifier("batchJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        registry.gauge("eventpulse.outbox.oldest.pending.seconds", outboxOldestPendingSeconds);
        registry.gauge("eventpulse.consumer.lag", consumerLag);
        registry.gauge("eventpulse.command.lease.age.seconds", commandLeaseAgeSeconds);
        registry.gauge("eventpulse.commands.manual.review", manualReviewCommands);
        registry.gauge("eventpulse.commands.unknown", unknownCommands);
        registry.gauge("eventpulse.inventory.equation.violations", inventoryEquationViolations);
        this.redeemAttempts = Counter.builder("eventpulse.ticket.redeem.attempts")
                .description("Ticket redemption attempts").register(registry);
        this.redeemRejections = Counter.builder("eventpulse.ticket.redeem.rejections")
                .description("Ticket redemption requests rejected by validation or business rules")
                .register(registry);
    }

    public void ticketRedeemAttempt() {
        redeemAttempts.increment();
    }

    public void ticketRedeemRejected() {
        redeemRejections.increment();
    }

    /** Refresh DB-backed gauges without consuming a transactional write connection. */
    @Scheduled(fixedDelayString = "${eventpulse.metrics.refresh-interval:PT15S}",
            initialDelayString = "${eventpulse.metrics.initial-delay:PT5S}")
    public void refresh() {
        set(outboxOldestPendingSeconds, """
                SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0)
                FROM outbox WHERE state = 'PENDING'
                """);
        set(consumerLag, """
                SELECT COALESCE(MAX(o.sequence - COALESCE(c.last_sequence, 0)), 0)
                FROM outbox o
                LEFT JOIN consumer_cursors c ON c.consumer = 'notification-consumer'
                  AND c.aggregate_type = o.aggregate_type AND c.aggregate_id = o.aggregate_id
                WHERE o.state = 'PUBLISHED'
                """);
        set(commandLeaseAgeSeconds, """
                SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(lease_acquired_at))), 0)
                FROM commands WHERE state = 'RUNNING' AND lease_acquired_at IS NOT NULL
                """);
        set(manualReviewCommands, "SELECT COUNT(*) FROM commands WHERE state = 'MANUAL_REVIEW'");
        set(unknownCommands, "SELECT COUNT(*) FROM commands WHERE state = 'UNKNOWN_QUERY'");
        set(inventoryEquationViolations, """
                SELECT COUNT(*) FROM inventory
                WHERE available + reserved + sold + withheld <> capacity
                """);
    }

    private void set(AtomicLong gauge, String sql) {
        Long value = number(sql);
        if (value != null) {
            gauge.set(value);
        }
    }

    private Long number(String sql) {
        try {
            Number result = jdbc.queryForObject(sql, Number.class);
            return result == null ? 0L : Math.max(0L, Math.round(result.doubleValue()));
        }
        catch (RuntimeException unavailable) {
            // Metrics must never take down a worker when the batch pool/database
            // is unavailable. Keep the last known value visible and let the
            // datasource/health meters report the underlying failure.
            return null;
        }
    }
}
