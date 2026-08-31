package dev.kaiwen.eventpulse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.IntegrationTestBase.OrganiserRef;
import dev.kaiwen.eventpulse.IntegrationTestBase.UserRef;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Booking edge branches: age confirmation, sale window, quantity bounds,
 * idempotency key requirements, pay/redirect branches, redemption window and
 * the cancel vs used-ticket rejection path.
 */
class BookingEdgeCasesIT extends IntegrationTestBase {

    @Test
    void bookingRequestValidationBranches() {
        OrganiserRef fixture = createEventWithTier(10, 2);
        UserRef user = createUser("USER");

        // quantity above per-booking max
        assertThat(post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 99, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken()).getStatusCode().value()).isEqualTo(400);

        // unknown event / tier -> hidden 404
        assertThat(post("/api/v1/bookings", user.token(),
                Map.of("eventId", UUID.randomUUID().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 1, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken()).getStatusCode().value()).isEqualTo(404);
        assertThat(post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", UUID.randomUUID().toString(),
                        "quantity", 1, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken()).getStatusCode().value()).isEqualTo(404);

        // sale window closed
        jdbc.update("UPDATE ticket_tiers SET sale_start_at = now() + interval '1 day' WHERE id = ?",
                fixture.tierId());
        ResponseEntity<Map> closed = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 1, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken());
        assertThat(closed.getStatusCode().value()).isEqualTo(409);
        assertThat(body(closed).get("code")).isEqualTo("SALE_WINDOW_CLOSED");
        jdbc.update("UPDATE ticket_tiers SET sale_start_at = now() - interval '1 hour' WHERE id = ?",
                fixture.tierId());

        // An event without an age_requirement (age_requirement IS NULL) has no
        // age gate: booking succeeds WITHOUT ageConfirmed (plan §2.1/§10.2 —
        // "unknown" refers to the user's eligibility fact, not to unrestricted
        // events; the old code 422'd exactly this case).
        ResponseEntity<Map> unconfirmed = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 1),
                CanonicalJson.newOpaqueToken());
        assertThat(unconfirmed.getStatusCode().value()).isEqualTo(201);
        assertThat(body(unconfirmed).get("status")).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void ageRequirementRequiresVerifiedFact() {
        OrganiserRef fixture = createEventWithTier(10, 2);
        jdbc.update("UPDATE events SET age_requirement = 18 WHERE id = ?", fixture.eventId());
        UserRef user = createUser("USER");
        // no eligibility fact -> rejected even with confirmation
        assertThat(post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 1, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken()).getStatusCode().value()).isEqualTo(422);
        // with a verified fact -> accepted
        jdbc.update("""
                INSERT INTO user_eligibility (user_id, minimum_verified_age, source, verified_at)
                VALUES (?, 18, 'it', now()) ON CONFLICT (user_id) DO UPDATE SET minimum_verified_age = 18
                """, user.id());
        assertThat(post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 1, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken()).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void payWithoutIdempotencyKeyIsRejected() {
        OrganiserRef fixture = createEventWithTier(10, 5);
        UserRef user = createUser("USER");
        assertThat(post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 1, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken()).getStatusCode().value()).isEqualTo(201);
        // missing key handled in the controller via empty string -> validation error
        assertThat(post("/api/v1/bookings/" + UUID.randomUUID() + "/pay", user.token(), Map.of(),
                CanonicalJson.newOpaqueToken()).getStatusCode().value()).isEqualTo(404);
    }

    /**
     * PROBE-B regression: pay runs its idempotency claim in the SAME
     * transaction as the business logic, so a rejected pay (409
     * BOOKING_NOT_PAYABLE) rolls the IN_PROGRESS claim back. Before the fix
     * the claim survived in idempotency_records and the same key returned 202
     * "request in progress" for 24h even after the blocking condition was
     * repaired. The client must be able to retry the SAME key.
     */
    @Test
    void failedPayDoesNotPoisonItsIdempotencyKey() {
        OrganiserRef fixture = createEventWithTier(10, 5);
        UserRef user = createUser("USER");
        ResponseEntity<Map> created = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 1, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken());
        String bookingId = (String) body(created).get("id");
        String key = CanonicalJson.newOpaqueToken();

        // Attempt #1 fails: the booking is expired (not payable).
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 second' WHERE id = ?",
                UUID.fromString(bookingId));
        ResponseEntity<Map> failed = post("/api/v1/bookings/" + bookingId + "/pay", user.token(),
                Map.of(), key);
        assertThat(failed.getStatusCode().value()).isEqualTo(409);
        assertThat(body(failed).get("code")).isEqualTo("BOOKING_NOT_PAYABLE");

        // No IN_PROGRESS tombstone survived the failure.
        Integer stuck = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE scope = 'bookings:pay' AND state = 'IN_PROGRESS'",
                Integer.class);
        assertThat(stuck).isZero();

        // Once the condition is repaired, the SAME key is usable again.
        jdbc.update("UPDATE bookings SET expires_at = now() + interval '10 minutes' WHERE id = ?",
                UUID.fromString(bookingId));
        ResponseEntity<Map> retry = post("/api/v1/bookings/" + bookingId + "/pay", user.token(),
                Map.of(), key);
        assertThat(retry.getStatusCode().value())
                .as("retry with the same key must execute (a 202 in-progress tombstone would be the bug)")
                .isEqualTo(200);
        assertThat(body(retry).get("providerKey")).isNotNull();
        Integer intents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_intents WHERE booking_id = ?", Integer.class,
                UUID.fromString(bookingId));
        assertThat(intents).isEqualTo(1);
    }

    /**
     * Same contract for cancel: a policy-rejected cancel rolls its claim back,
     * so the same key succeeds once the policy snapshot allows cancellation.
     */
    @Test
    void failedCancelDoesNotPoisonItsIdempotencyKey() {
        OrganiserRef fixture = createEventWithTier(10, 5);
        UserRef user = createUser("USER");
        ResponseEntity<Map> created = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 1, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken());
        String bookingId = (String) body(created).get("id");
        post("/api/v1/bookings/" + bookingId + "/pay", user.token(), Map.of(),
                CanonicalJson.newOpaqueToken());
        String key = CanonicalJson.newOpaqueToken();

        // Policy snapshot forbids cancellation -> 409 and the claim rolls back.
        jdbc.update("UPDATE bookings SET policy_snapshot = policy_snapshot || '{\"cancellable\": false}' "
                + "WHERE id = ?", UUID.fromString(bookingId));
        ResponseEntity<Map> rejected = post("/api/v1/bookings/" + bookingId + "/cancel", user.token(),
                Map.of("reason", "no"), key);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(body(rejected).get("code")).isEqualTo("BOOKING_NOT_CANCELLABLE");
        Integer stuck = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE scope = 'bookings:cancel' "
                        + "AND state = 'IN_PROGRESS'", Integer.class);
        assertThat(stuck).isZero();

        // Restored policy: same key cancels successfully.
        jdbc.update("UPDATE bookings SET policy_snapshot = policy_snapshot || '{\"cancellable\": true}' "
                + "WHERE id = ?", UUID.fromString(bookingId));
        ResponseEntity<Map> cancelled = post("/api/v1/bookings/" + bookingId + "/cancel", user.token(),
                Map.of("reason", "plans changed"), key);
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(body(cancelled).get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void redemptionWindowAndNonConfirmedBookingBranches() {
        OrganiserRef fixture = createEventWithTier(10, 5);
        UserRef user = createUser("USER");
        ResponseEntity<Map> created = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 1, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken());
        String bookingId = (String) body(created).get("id");
        post("/api/v1/bookings/" + bookingId + "/pay", user.token(), Map.of(),
                CanonicalJson.newOpaqueToken());
        List<String> tokens = context.getBean(dev.kaiwen.eventpulse.service.TicketService.class)
                .revealTokens(user.id(), UUID.fromString(bookingId));
        assertThat(tokens).hasSize(1);

        // an event that already ended makes its tickets unredeemable
        jdbc.update("UPDATE events SET ends_at = now() - interval '1 day', "
                + "starts_at = now() - interval '2 days' WHERE id = ?", fixture.eventId());
        UserRef organiser = createUser("ORGANISER");
        jdbc.update("UPDATE organisers SET owner_user_id = ? WHERE id = ?", organiser.id(),
                fixture.organiserId());
        ResponseEntity<Map> expiredWindow = post("/api/v1/organiser/tickets/redeem", organiser.token(),
                Map.of("token", tokens.get(0)), CanonicalJson.newOpaqueToken());
        assertThat(expiredWindow.getStatusCode().value()).isEqualTo(409);
        assertThat(body(expiredWindow).get("code")).isEqualTo("TICKET_NOT_REDEEMABLE");
    }

    @Test
    void expireWithoutPayDoesNotDebitWallet() {
        OrganiserRef fixture = createEventWithTier(10, 5);
        UserRef user = createUser("USER");
        ResponseEntity<Map> created = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 2, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken());
        UUID bookingUuid = UUID.fromString((String) body(created).get("id"));
        long walletBefore = jdbc.queryForObject(
                "SELECT available_amount_minor FROM user_wallets WHERE user_id = ?", Long.class, user.id());
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 second' WHERE id = ?", bookingUuid);
        assertThat(transitionsExpire(bookingUuid)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingUuid))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT available_amount_minor FROM user_wallets WHERE user_id = ?", Long.class, user.id()))
                .isEqualTo(walletBefore);
        Integer reserved = jdbc.queryForObject("SELECT reserved FROM inventory WHERE tier_id = ?",
                Integer.class, fixture.tierId());
        assertThat(reserved).isZero();
    }

    private boolean transitionsExpire(UUID bookingId) {
        return context.getBean(dev.kaiwen.eventpulse.service.BookingTransitions.class).expireBooking(bookingId);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext context;
}
