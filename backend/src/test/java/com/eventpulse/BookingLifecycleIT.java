package com.eventpulse;

import java.util.Map;
import java.util.UUID;

import com.eventpulse.booking.BookingTransitions;
import com.eventpulse.payment.CommandDispatcher;
import com.eventpulse.IntegrationTestBase.OrganiserRef;
import com.eventpulse.IntegrationTestBase.UserRef;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import com.eventpulse.common.CanonicalJson;

/**
 * Payment single flight, confirm, expiry vs late capture, cancellation and
 * the refund reservation invariant - all driven by explicit dispatcher/
 * scheduler ticks for determinism.
 */
class BookingLifecycleIT extends IntegrationTestBase {

    @Autowired
    private CommandDispatcher dispatcher;

    @Autowired
    private BookingTransitions transitions;

    private String createAndPay(UserRef user, OrganiserRef fixture) {
        ResponseEntity<Map> created = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 2, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken());
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String bookingId = (String) body(created).get("id");
        ResponseEntity<Map> pay = post("/api/v1/bookings/" + bookingId + "/pay", user.token(), Map.of(),
                CanonicalJson.newOpaqueToken());
        assertThat(pay.getStatusCode().value()).isEqualTo(200);
        assertThat(body(pay).get("providerKey")).isNotNull();
        return bookingId;
    }

    @Test
    void twoPaymentKeysYieldOneActiveIntent() {
        OrganiserRef fixture = createEventWithTier(100, 10);
        UserRef user = createUser("USER");
        ResponseEntity<Map> created = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 1, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken());
        String bookingId = (String) body(created).get("id");

        ResponseEntity<Map> payOne = post("/api/v1/bookings/" + bookingId + "/pay", user.token(), Map.of(),
                CanonicalJson.newOpaqueToken());
        ResponseEntity<Map> payTwo = post("/api/v1/bookings/" + bookingId + "/pay", user.token(), Map.of(),
                CanonicalJson.newOpaqueToken());
        assertThat(payOne.getStatusCode().value()).isEqualTo(200);
        assertThat(payTwo.getStatusCode().value()).isEqualTo(200);
        assertThat(body(payTwo).get("id")).isEqualTo(body(payOne).get("id"));

        Integer activeIntents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_intents WHERE booking_id = ? AND active = TRUE", Integer.class,
                UUID.fromString(bookingId));
        assertThat(activeIntents).isEqualTo(1);
    }

    @Test
    void successfulCaptureConfirmsBookingIssuesTicketsAndMovesStock() {
        OrganiserRef fixture = createEventWithTier(100, 10);
        UserRef user = createUser("USER");
        String bookingId = createAndPay(user, fixture);

        dispatcher.tick();

        Map<String, Object> booking = jdbc.queryForMap("SELECT status, entitlement_status, refund_state "
                + "FROM bookings WHERE id = ?", UUID.fromString(bookingId));
        assertThat(booking.get("status")).isEqualTo("CONFIRMED");
        assertThat(booking.get("entitlement_status")).isEqualTo("ACTIVE");
        Integer tickets = jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE booking_id = ?", Integer.class,
                UUID.fromString(bookingId));
        assertThat(tickets).isEqualTo(2);
        assertInventoryInvariant(fixture.tierId());
        Map<String, Object> inventory = inventoryRow(fixture.tierId());
        assertThat(((Number) inventory.get("sold")).longValue()).isEqualTo(2);
        assertThat(((Number) inventory.get("reserved")).longValue()).isZero();
        Integer confirmedQuota = jdbc.queryForObject(
                "SELECT confirmed_quantity FROM user_tier_quota WHERE user_id = ? AND tier_id = ?",
                Integer.class, user.id(), fixture.tierId());
        assertThat(confirmedQuota).isEqualTo(2);
        Map<String, Object> balance = jdbc.queryForMap(
                "SELECT captured_amount_minor, refund_reserved_amount_minor, refunded_amount_minor "
                        + "FROM payment_balance WHERE booking_id = ?", UUID.fromString(bookingId));
        assertThat(((Number) balance.get("captured_amount_minor")).longValue()).isEqualTo(20000L);
    }

    @Test
    void expiryReleasesStockAndLateCaptureIsCompensatedWithRefund() {
        OrganiserRef fixture = createEventWithTier(100, 10);
        UserRef user = createUser("USER");
        String bookingId = createAndPay(user, fixture);
        UUID bookingUuid = UUID.fromString(bookingId);

        // Simulate a capture command claimed by a dispatcher that is about to die.
        jdbc.update("UPDATE commands SET state = 'RUNNING', lease_until = now() - interval '1 minute', "
                + "attempts = attempts + 1 WHERE kind = 'CAPTURE' AND aggregate_id = ?", bookingUuid);

        // The booking expires (payment never arrived in time).
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 second' WHERE id = ?", bookingUuid);
        boolean expired = transitions.expireBooking(bookingUuid);
        assertThat(expired).isTrue();

        Map<String, Object> afterExpiry = jdbc.queryForMap("SELECT status, entitlement_status FROM bookings "
                + "WHERE id = ?", bookingUuid);
        assertThat(afterExpiry.get("status")).isEqualTo("EXPIRED");
        assertInventoryInvariant(fixture.tierId());
        Integer reserved = jdbc.queryForObject("SELECT reserved FROM inventory WHERE tier_id = ?",
                Integer.class, fixture.tierId());
        assertThat(reserved).isZero();

        // The capture actually succeeds late at the gateway: the dispatcher
        // must compensate with an automatic refund command, never a confirm.
        dispatcher.tick();

        String captureEffect = jdbc.queryForObject(
                "SELECT result::text FROM commands WHERE kind = 'CAPTURE' AND aggregate_id = ?", String.class,
                bookingUuid);
        assertThat(captureEffect).contains("late_capture");

        // The refund command completes on the next tick.
        dispatcher.tick();

        Map<String, Object> balance = jdbc.queryForMap("""
                SELECT captured_amount_minor, refund_reserved_amount_minor, refunded_amount_minor
                FROM payment_balance WHERE booking_id = ?
                """, bookingUuid);
        long captured = ((Number) balance.get("captured_amount_minor")).longValue();
        long reservedRefund = ((Number) balance.get("refund_reserved_amount_minor")).longValue();
        long refunded = ((Number) balance.get("refunded_amount_minor")).longValue();
        assertThat(captured).isEqualTo(20000L);
        assertThat(refunded).isEqualTo(captured);
        assertThat(reservedRefund).isZero();
        Map<String, Object> booking = jdbc.queryForMap("SELECT refund_state FROM bookings WHERE id = ?",
                bookingUuid);
        assertThat(booking.get("refund_state")).isEqualTo("REFUNDED");
        Integer tickets = jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE booking_id = ?", Integer.class,
                bookingUuid);
        assertThat(tickets).isZero(); // no tickets for a terminated order
    }

    @Test
    void cancelAfterConfirmReservesRefundThenCompletes() {
        OrganiserRef fixture = createEventWithTier(100, 10);
        UserRef user = createUser("USER");
        String bookingId = createAndPay(user, fixture);
        UUID bookingUuid = UUID.fromString(bookingId);
        dispatcher.tick();
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingUuid))
                .isEqualTo("CONFIRMED");

        ResponseEntity<Map> cancelled = post("/api/v1/bookings/" + bookingId + "/cancel", user.token(),
                Map.of("reason", "plans changed"), CanonicalJson.newOpaqueToken());
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(body(cancelled).get("status")).isEqualTo("CANCELLATION_PENDING");

        // Entitlement revoked immediately; refund amount reserved atomically.
        Integer activeTickets = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE booking_id = ? AND status = 'ACTIVE'", Integer.class,
                bookingUuid);
        assertThat(activeTickets).isZero();
        Map<String, Object> balance = jdbc.queryForMap("""
                SELECT captured_amount_minor, refund_reserved_amount_minor, refunded_amount_minor
                FROM payment_balance WHERE booking_id = ?
                """, bookingUuid);
        assertThat(((Number) balance.get("refund_reserved_amount_minor")).longValue()).isEqualTo(20000L);

        dispatcher.tick();

        Map<String, Object> afterRefund = jdbc.queryForMap("SELECT status, refund_state FROM bookings "
                + "WHERE id = ?", bookingUuid);
        assertThat(afterRefund.get("status")).isEqualTo("CANCELLED");
        assertThat(afterRefund.get("refund_state")).isEqualTo("REFUNDED");
        Map<String, Object> balanceAfter = jdbc.queryForMap(
                "SELECT refund_reserved_amount_minor, refunded_amount_minor FROM payment_balance "
                        + "WHERE booking_id = ?", bookingUuid);
        assertThat(((Number) balanceAfter.get("refund_reserved_amount_minor")).longValue()).isZero();
        assertThat(((Number) balanceAfter.get("refunded_amount_minor")).longValue()).isEqualTo(20000L);
        assertInventoryInvariant(fixture.tierId());
        // Non-resale policy: stock moves to withheld, never back to available.
        Map<String, Object> inventory = inventoryRow(fixture.tierId());
        assertThat(((Number) inventory.get("withheld")).longValue()).isEqualTo(2);
    }

    @Test
    void failedCaptureReleasesEverythingWithoutTickets() {
        OrganiserRef fixture = createEventWithTier(100, 10);
        UserRef user = createUser("USER");
        // Force-fail via gateway scenario rule on the provider key prefix is
        // configured server-side; here we simulate a gateway failure directly.
        String bookingId = createAndPay(user, fixture);
        UUID bookingUuid = UUID.fromString(bookingId);
        jdbc.update("INSERT INTO gateway_results (provider_key, kind, amount_minor, scenario, status, "
                + "available_at) SELECT provider_key, 'CAPTURE', requested_amount_minor, 'FAILURE', 'FAILED', now() "
                + "FROM payment_intents WHERE booking_id = ?", bookingUuid);
        jdbc.update("UPDATE gateway_results SET status = 'FAILED' WHERE provider_key IN "
                + "(SELECT provider_key FROM payment_intents WHERE booking_id = ?)", bookingUuid);
        dispatcher.tick();

        Map<String, Object> booking = jdbc.queryForMap("SELECT status, entitlement_status FROM bookings "
                + "WHERE id = ?", bookingUuid);
        assertThat(booking.get("status")).isEqualTo("PAYMENT_FAILED");
        assertInventoryInvariant(fixture.tierId());
        Integer available = jdbc.queryForObject("SELECT available FROM inventory WHERE tier_id = ?",
                Integer.class, fixture.tierId());
        assertThat(available).isEqualTo(100);
        Integer tickets = jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE booking_id = ?", Integer.class,
                bookingUuid);
        assertThat(tickets).isZero();
        Integer active = jdbc.queryForObject(
                "SELECT active_quantity FROM user_tier_quota WHERE user_id = ? AND tier_id = ?", Integer.class,
                user.id(), fixture.tierId());
        assertThat(active).isZero();
    }
}
