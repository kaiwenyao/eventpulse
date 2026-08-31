package dev.kaiwen.eventpulse;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import dev.kaiwen.eventpulse.payment.CommandDispatcher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin recovery surface end to end: reauth freshness, exception overview,
 * command retry, consumer gap resolution (dry-run / REPLAY / SKIP approval),
 * outbox replay and the human refund waiver.
 */
class AdminApiIT extends IntegrationTestBase {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CommandDispatcher dispatcher;

    private UserRef admin;
    private String reauthToken;

    void setupAdmin() {
        admin = createUser("ADMIN");
        // fixture users carry a placeholder hash; give the admin a real one
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?",
                passwordEncoder.encode("AdminIt!234567890"), admin.id());
        ResponseEntity<Map> reauth = post("/api/v1/admin/reauth", admin.token(),
                Map.of("password", "AdminIt!234567890"));
        assertThat(reauth.getStatusCode().value()).isEqualTo(200);
        reauthToken = (String) reauth.getBody().get("reauthToken");
        assertThat(reauthToken).isNotBlank();
    }

    @Test
    void reauthGateBlocksAndThenAllowsExceptionOverview() {
        setupAdmin();

        // without reauth token -> 403 REAUTH_REQUIRED
        assertThat(get("/api/v1/admin/exceptions", admin.token()).getStatusCode().value()).isEqualTo(403);
        // wrong reauth token -> 403
        assertThat(exchange("GET", "/api/v1/admin/exceptions", admin.token(), null,
                Map.of("X-Reauth-Token", "bogus")).getStatusCode().value()).isEqualTo(403);
        // wrong password -> 401
        assertThat(post("/api/v1/admin/reauth", admin.token(), Map.of("password", "wrong-password"))
                .getStatusCode().value()).isEqualTo(401);

        ResponseEntity<Map> exceptions = exchange("GET", "/api/v1/admin/exceptions", admin.token(), null,
                Map.of("X-Reauth-Token", reauthToken));
        assertThat(exceptions.getStatusCode().value()).isEqualTo(200);
        assertThat(body(exceptions)).containsKeys("manualReviewCommands", "unknownCommands", "failedRefunds",
                "openConsumerGaps", "outboxOldestPendingSeconds");
    }

    @Test
    void retryManualReviewCommandWithOriginalProviderKey() {
        setupAdmin();
        UUID commandId = jdbc.queryForObject("""
                INSERT INTO commands (kind, aggregate_type, aggregate_id, provider_key, state, last_error)
                VALUES ('CAPTURE', 'booking', ?, 'pi-it-manual-1', 'MANUAL_REVIEW', 'stuck')
                RETURNING id
                """, UUID.class, UUID.randomUUID());
        assertThat(exchange("POST", "/api/v1/admin/commands/" + commandId + "/retry", admin.token(),
                Map.of("reason", "it"), Map.of("X-Reauth-Token", reauthToken)).getStatusCode().value())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT state FROM commands WHERE id = ?", String.class, commandId))
                .isEqualTo("READY");
        // retry again -> 404 (no longer in manual review)
        assertThat(exchange("POST", "/api/v1/admin/commands/" + commandId + "/retry", admin.token(),
                Map.of("reason", "it"), Map.of("X-Reauth-Token", reauthToken)).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void resolveConsumerGapDryRunReplayAndSkipApproval() {
        setupAdmin();
        UUID aggregateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO consumer_cursors (consumer, aggregate_type, aggregate_id, last_sequence)
                VALUES ('notification-consumer', 'booking', ?, 0)
                """, aggregateId);
        UUID gapId = jdbc.queryForObject("""
                INSERT INTO consumer_gaps (consumer, aggregate_type, aggregate_id, expected, received)
                VALUES ('notification-consumer', 'booking', ?, 1, 2)
                RETURNING id
                """, UUID.class, aggregateId);

        Map<String, String> reauthHeader = Map.of("X-Reauth-Token", reauthToken);

        // dry-run changes nothing
        ResponseEntity<Map> dryRun = exchange("POST", "/api/v1/admin/consumer-gaps/" + gapId + "/resolve",
                admin.token(), Map.of("strategy", "REPLAY", "note", "it", "dryRun", true), reauthHeader);
        assertThat(dryRun.getStatusCode().value()).isEqualTo(200);
        assertThat(body(dryRun).get("dryRun")).isEqualTo(true);

        // REPLAY resolves and re-marks outbox rows for the aggregate
        ResponseEntity<Map> replay = exchange("POST", "/api/v1/admin/consumer-gaps/" + gapId + "/resolve",
                admin.token(), Map.of("strategy", "REPLAY", "note", "it"), reauthHeader);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT state FROM consumer_gaps WHERE id = ?", String.class, gapId))
                .isEqualTo("RESOLVED_REPLAY");

        // SKIP without approver -> 400
        UUID skipGap = jdbc.queryForObject("""
                INSERT INTO consumer_gaps (consumer, aggregate_type, aggregate_id, expected, received)
                VALUES ('notification-consumer', 'booking', ?, 3, 4)
                RETURNING id
                """, UUID.class, aggregateId);
        ResponseEntity<Map> skipRejected = exchange("POST", "/api/v1/admin/consumer-gaps/" + skipGap
                + "/resolve", admin.token(), Map.of("strategy", "SKIP", "note", "it"), reauthHeader);
        assertThat(skipRejected.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<Map> skip = exchange("POST", "/api/v1/admin/consumer-gaps/" + skipGap + "/resolve",
                admin.token(), Map.of("strategy", "SKIP", "note", "it", "approvedBy", "second-admin"), reauthHeader);
        assertThat(skip.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT last_sequence FROM consumer_cursors WHERE aggregate_id = ?",
                Long.class, aggregateId)).isEqualTo(4L);

        // unknown gap -> 404
        assertThat(exchange("POST", "/api/v1/admin/consumer-gaps/" + UUID.randomUUID() + "/resolve",
                admin.token(), Map.of("strategy", "REPLAY"), reauthHeader).getStatusCode().value())
                .isEqualTo(404);
        // unknown strategy -> 400
        ResponseEntity<Map> bogus = exchange("POST", "/api/v1/admin/consumer-gaps/" + skipGap + "/resolve",
                admin.token(), Map.of("strategy", "NOPE"), reauthHeader);
        assertThat(bogus.getStatusCode().value()).isEqualTo(404); // gap already resolved
    }

    @Test
    void outboxReplayRequeuesPublishedEvents() {
        setupAdmin();
        UUID aggregateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox (aggregate_type, aggregate_id, sequence, topic, event_type, payload, state)
                VALUES ('booking', ?, 1, 'booking.events.v1', 'booking.created', '{}', 'PUBLISHED')
                """, aggregateId);

        ResponseEntity<Map> dryRun = exchange("POST", "/api/v1/admin/outbox/replay", admin.token(),
                Map.of("aggregateType", "booking", "aggregateId", aggregateId.toString(), "dryRun", true),
                Map.of("X-Reauth-Token", reauthToken));
        assertThat(dryRun.getStatusCode().value()).isEqualTo(200);
        assertThat(((Number) body(dryRun).get("wouldReplay")).intValue()).isEqualTo(1);

        ResponseEntity<Map> replay = exchange("POST", "/api/v1/admin/outbox/replay", admin.token(),
                Map.of("aggregateId", aggregateId.toString()), Map.of("X-Reauth-Token", reauthToken));
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT state FROM outbox WHERE aggregate_id = ?", String.class,
                aggregateId)).isEqualTo("PENDING");
    }

    @Test
    void abandonFailedRefundReleasesReservationWithAudit() {
        setupAdmin();
        OrganiserRef fixture = createEventWithTier(10, 5);
        UUID buyer = createUser("USER").id();
        UUID bookingId = jdbc.queryForObject("""
                INSERT INTO bookings (user_id, event_id, tier_id, quantity, status, unit_price_minor,
                                      currency, policy_snapshot, price_snapshot)
                VALUES (?, ?, ?, 1, 'CANCELLATION_PENDING', 10000, 'CNY', '{}', '{}')
                RETURNING id
                """, UUID.class, buyer, fixture.eventId(), fixture.tierId());
        UUID intentId = jdbc.queryForObject("""
                INSERT INTO payment_intents (booking_id, attempt_no, state, requested_amount_minor,
                                             currency, provider_key, active)
                VALUES (?, 1, 'SUCCEEDED', 10000, 'CNY', 'pi-it-abandon-1', FALSE)
                RETURNING id
                """, UUID.class, bookingId);
        jdbc.update("""
                INSERT INTO payment_balance (booking_id, currency, captured_amount_minor,
                                             refund_reserved_amount_minor)
                VALUES (?, 'CNY', 10000, 10000)
                """, bookingId);
        UUID refundId = jdbc.queryForObject("""
                INSERT INTO refunds (payment_id, booking_id, amount_minor, state, command_id)
                VALUES (?, ?, 10000, 'FAILED', ?)
                RETURNING id
                """, UUID.class, intentId, bookingId, UUID.randomUUID());

        ResponseEntity<Map> abandoned = exchange("POST", "/api/v1/admin/refunds/" + refundId + "/abandon",
                admin.token(), Map.of("reason", "waived by support"), Map.of("X-Reauth-Token", reauthToken));
        assertThat(abandoned.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT refund_reserved_amount_minor FROM payment_balance WHERE booking_id = ?", Long.class,
                bookingId)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM refunds WHERE id = ?", String.class, refundId))
                .isEqualTo("ABANDONED");
        Integer auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'admin.refund.abandon'", Integer.class);
        assertThat(auditRows).isGreaterThanOrEqualTo(1);

        // abandoning a pending refund -> 409
        UUID pendingRefund = jdbc.queryForObject("""
                INSERT INTO refunds (payment_id, booking_id, amount_minor, state, command_id)
                VALUES ((SELECT id FROM payment_intents WHERE booking_id = ? LIMIT 1), ?, 10000, 'PENDING', ?)
                RETURNING id
                """, UUID.class, bookingId, bookingId, UUID.randomUUID());
        assertThat(exchange("POST", "/api/v1/admin/refunds/" + pendingRefund + "/abandon", admin.token(),
                Map.of("reason", "nope"), Map.of("X-Reauth-Token", reauthToken)).getStatusCode().value())
                .isEqualTo(409);
    }

    @Test
    void dispatcherManualRetryPicksUpCommandsAgain() {
        setupAdmin();
        UUID commandId = jdbc.queryForObject("""
                INSERT INTO commands (kind, aggregate_type, aggregate_id, provider_key, state)
                VALUES ('CAPTURE', 'booking', ?, 'pi-it-dispatch-unknown', 'UNKNOWN_QUERY')
                RETURNING id
                """, UUID.class, UUID.randomUUID());
        // gateway_results: pending forever (ALWAYS_UNKNOWN-like) -> stays in query loop
        jdbc.update("""
                INSERT INTO gateway_results (provider_key, kind, amount_minor, scenario, status, available_at)
                VALUES ('pi-it-dispatch-unknown', 'CAPTURE', 0, 'ALWAYS_UNKNOWN', 'PENDING',
                        now() + interval '365 days')
                """);
        dispatcher.tick();
        // command either still UNKNOWN_QUERY (not yet claimable) or retried; never guessed
        String state = jdbc.queryForObject("SELECT state FROM commands WHERE id = ?", String.class,
                commandId);
        assertThat(state).isIn("UNKNOWN_QUERY", "MANUAL_REVIEW", "READY");
    }
}
