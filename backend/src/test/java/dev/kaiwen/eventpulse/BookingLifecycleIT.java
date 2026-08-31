package dev.kaiwen.eventpulse;

import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.service.BookingTransitions;
import dev.kaiwen.eventpulse.IntegrationTestBase.OrganiserRef;
import dev.kaiwen.eventpulse.IntegrationTestBase.UserRef;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import dev.kaiwen.eventpulse.common.CanonicalJson;

/**
 * Payment single flight, sync wallet confirm, expiry vs pay, cancellation
 * and the refund reservation invariant.
 */
class BookingLifecycleIT extends IntegrationTestBase {

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
        assertThat(body(pay).get("state")).isEqualTo("SUCCEEDED");
        return bookingId;
    }

    @Test
    void twoPaymentKeysYieldOneIntentAndSecondPayIsRejected() {
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
        assertThat(payTwo.getStatusCode().value()).isEqualTo(409);
        assertThat(body(payTwo).get("code")).isEqualTo("BOOKING_NOT_PAYABLE");

        Integer intents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_intents WHERE booking_id = ?", Integer.class,
                UUID.fromString(bookingId));
        assertThat(intents).isEqualTo(1);
        Integer activeIntents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_intents WHERE booking_id = ? AND active = TRUE", Integer.class,
                UUID.fromString(bookingId));
        assertThat(activeIntents).isZero();
    }

    @Test
    void successfulPayConfirmsBookingIssuesTicketsAndMovesStock() {
        OrganiserRef fixture = createEventWithTier(100, 10);
        UserRef user = createUser("USER");
        long walletBefore = jdbc.queryForObject(
                "SELECT available_amount_minor FROM user_wallets WHERE user_id = ?", Long.class, user.id());
        String bookingId = createAndPay(user, fixture);

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
        long walletAfter = jdbc.queryForObject(
                "SELECT available_amount_minor FROM user_wallets WHERE user_id = ?", Long.class, user.id());
        assertThat(walletAfter).isEqualTo(walletBefore - 20000L);
    }

    @Test
    void expiryReleasesStockWithoutDebitingWalletAndPayThenFails() {
        OrganiserRef fixture = createEventWithTier(100, 10);
        UserRef user = createUser("USER");
        ResponseEntity<Map> created = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 2, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken());
        UUID bookingUuid = UUID.fromString((String) body(created).get("id"));
        long walletBefore = jdbc.queryForObject(
                "SELECT available_amount_minor FROM user_wallets WHERE user_id = ?", Long.class, user.id());

        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 second' WHERE id = ?", bookingUuid);
        assertThat(transitions.expireBooking(bookingUuid)).isTrue();

        Map<String, Object> afterExpiry = jdbc.queryForMap("SELECT status, entitlement_status FROM bookings "
                + "WHERE id = ?", bookingUuid);
        assertThat(afterExpiry.get("status")).isEqualTo("EXPIRED");
        assertInventoryInvariant(fixture.tierId());
        Integer reserved = jdbc.queryForObject("SELECT reserved FROM inventory WHERE tier_id = ?",
                Integer.class, fixture.tierId());
        assertThat(reserved).isZero();
        long walletAfter = jdbc.queryForObject(
                "SELECT available_amount_minor FROM user_wallets WHERE user_id = ?", Long.class, user.id());
        assertThat(walletAfter).isEqualTo(walletBefore);

        ResponseEntity<Map> pay = post("/api/v1/bookings/" + bookingUuid + "/pay", user.token(), Map.of(),
                CanonicalJson.newOpaqueToken());
        assertThat(pay.getStatusCode().value()).isEqualTo(409);
        assertThat(body(pay).get("code")).isEqualTo("BOOKING_NOT_PAYABLE");
        assertThat(jdbc.queryForObject(
                "SELECT available_amount_minor FROM user_wallets WHERE user_id = ?", Long.class, user.id()))
                .isEqualTo(walletBefore);
    }

    @Test
    void cancelAfterConfirmCreditsWalletAndCompletesRefund() {
        OrganiserRef fixture = createEventWithTier(100, 10);
        UserRef user = createUser("USER");
        long walletBefore = jdbc.queryForObject(
                "SELECT available_amount_minor FROM user_wallets WHERE user_id = ?", Long.class, user.id());
        String bookingId = createAndPay(user, fixture);
        UUID bookingUuid = UUID.fromString(bookingId);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingUuid))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "SELECT available_amount_minor FROM user_wallets WHERE user_id = ?", Long.class, user.id()))
                .isEqualTo(walletBefore - 20000L);

        ResponseEntity<Map> cancelled = post("/api/v1/bookings/" + bookingId + "/cancel", user.token(),
                Map.of("reason", "plans changed"), CanonicalJson.newOpaqueToken());
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(body(cancelled).get("status")).isEqualTo("CANCELLED");
        assertThat(body(cancelled).get("refundState")).isEqualTo("REFUNDED");

        Integer activeTickets = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE booking_id = ? AND status = 'ACTIVE'", Integer.class,
                bookingUuid);
        assertThat(activeTickets).isZero();
        Map<String, Object> balanceAfter = jdbc.queryForMap(
                "SELECT captured_amount_minor, refund_reserved_amount_minor, refunded_amount_minor "
                        + "FROM payment_balance WHERE booking_id = ?", bookingUuid);
        assertThat(((Number) balanceAfter.get("captured_amount_minor")).longValue()).isEqualTo(20000L);
        assertThat(((Number) balanceAfter.get("refund_reserved_amount_minor")).longValue()).isZero();
        assertThat(((Number) balanceAfter.get("refunded_amount_minor")).longValue()).isEqualTo(20000L);
        assertThat(jdbc.queryForObject(
                "SELECT available_amount_minor FROM user_wallets WHERE user_id = ?", Long.class, user.id()))
                .isEqualTo(walletBefore);
        assertInventoryInvariant(fixture.tierId());
        Map<String, Object> inventory = inventoryRow(fixture.tierId());
        assertThat(((Number) inventory.get("withheld")).longValue()).isEqualTo(2);
    }

    @Test
    void insufficientBalanceLeavesBookingPendingAndStockReserved() {
        OrganiserRef fixture = createEventWithTier(100, 10);
        UserRef user = createUser("USER");
        jdbc.update("UPDATE user_wallets SET available_amount_minor = 0 WHERE user_id = ?", user.id());
        ResponseEntity<Map> created = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 2, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken());
        String bookingId = (String) body(created).get("id");
        UUID bookingUuid = UUID.fromString(bookingId);

        ResponseEntity<Map> pay = post("/api/v1/bookings/" + bookingId + "/pay", user.token(), Map.of(),
                CanonicalJson.newOpaqueToken());
        assertThat(pay.getStatusCode().value()).isEqualTo(409);
        assertThat(body(pay).get("code")).isEqualTo("INSUFFICIENT_BALANCE");

        Map<String, Object> booking = jdbc.queryForMap("SELECT status, entitlement_status FROM bookings "
                + "WHERE id = ?", bookingUuid);
        assertThat(booking.get("status")).isEqualTo("PAYMENT_PENDING");
        assertInventoryInvariant(fixture.tierId());
        Integer reserved = jdbc.queryForObject("SELECT reserved FROM inventory WHERE tier_id = ?",
                Integer.class, fixture.tierId());
        assertThat(reserved).isEqualTo(2);
        Integer tickets = jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE booking_id = ?", Integer.class,
                bookingUuid);
        assertThat(tickets).isZero();
        Integer intents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_intents WHERE booking_id = ?", Integer.class, bookingUuid);
        assertThat(intents).isZero();
    }
}
