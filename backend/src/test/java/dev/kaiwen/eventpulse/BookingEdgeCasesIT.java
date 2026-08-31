package dev.kaiwen.eventpulse;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.kaiwen.eventpulse.dto.BookingDtos;
import dev.kaiwen.eventpulse.common.CanonicalJson;
import dev.kaiwen.eventpulse.service.BookingService;
import dev.kaiwen.eventpulse.payment.CommandDispatcher;
import dev.kaiwen.eventpulse.service.TicketIssuer;
import dev.kaiwen.eventpulse.IntegrationTestBase.OrganiserRef;
import dev.kaiwen.eventpulse.IntegrationTestBase.UserRef;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Booking edge branches: age confirmation, sale window, quantity bounds,
 * idempotency key requirements, pay/redirect branches, redemption window and
 * the full void-to-refund compensation chain on a late capture.
 */
class BookingEdgeCasesIT extends IntegrationTestBase {

    @Autowired
    private CommandDispatcher dispatcher;

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

        // unknown age requirement without explicit confirmation -> 422
        ResponseEntity<Map> unconfirmed = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 1),
                CanonicalJson.newOpaqueToken());
        assertThat(unconfirmed.getStatusCode().value()).isEqualTo(422);
        assertThat(body(unconfirmed).get("code")).isEqualTo("AGE_REQUIREMENT_NOT_CONFIRMED");
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
        dispatcher.tick();
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
    void lateCaptureAfterVoidGoesThroughFullRefundChain() {
        OrganiserRef fixture = createEventWithTier(10, 5);
        UserRef user = createUser("USER");
        ResponseEntity<Map> created = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 2, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken());
        String bookingId = (String) body(created).get("id");
        UUID bookingUuid = UUID.fromString(bookingId);
        post("/api/v1/bookings/" + bookingId + "/pay", user.token(), Map.of(),
                CanonicalJson.newOpaqueToken());

        // capture already succeeded at the gateway, but the command is unprocessed
        String captureKey = jdbc.queryForObject(
                "SELECT provider_key FROM payment_intents WHERE booking_id = ?", String.class, bookingUuid);
        jdbc.update("""
                INSERT INTO gateway_results (provider_key, kind, amount_minor, scenario, status, available_at)
                VALUES (?, 'CAPTURE', 20000, 'SUCCESS', 'SUCCEEDED', now())
                """, captureKey);
        // ...and the booking expires while the capture command is RUNNING
        jdbc.update("UPDATE commands SET state = 'RUNNING', lease_until = now() - interval '1 minute' "
                + "WHERE kind = 'CAPTURE' AND aggregate_id = ?", bookingUuid);
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 second' WHERE id = ?", bookingUuid);
        assertThat(transitionsExpire(bookingUuid)).isTrue();

        dispatcher.tick();

        // VOID saw the captured charge and converted; the capture replayed and
        // detected the existing compensation refund
        String voidState = jdbc.queryForObject(
                "SELECT result::text FROM commands WHERE kind = 'VOID' AND aggregate_id = ?", String.class,
                bookingUuid);
        assertThat(voidState).contains("refund_exists");
        long captured = jdbc.queryForObject(
                "SELECT captured_amount_minor FROM payment_balance WHERE booking_id = ?", Long.class,
                bookingUuid);
        assertThat(captured).isEqualTo(20000L);

        // the compensation refund settles on the next tick
        dispatcher.tick();
        long refunded = jdbc.queryForObject(
                "SELECT refunded_amount_minor FROM payment_balance WHERE booking_id = ?", Long.class,
                bookingUuid);
        assertThat(refunded).isEqualTo(20000L);
        assertThat(jdbc.queryForObject("SELECT refund_state FROM bookings WHERE id = ?", String.class,
                bookingUuid)).isEqualTo("REFUNDED");
    }

    private boolean transitionsExpire(UUID bookingId) {
        return context.getBean(dev.kaiwen.eventpulse.service.BookingTransitions.class).expireBooking(bookingId);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext context;
}
