package dev.kaiwen.eventpulse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.IntegrationTestBase.OrganiserRef;
import dev.kaiwen.eventpulse.IntegrationTestBase.UserRef;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import dev.kaiwen.eventpulse.payment.CommandDispatcher;
import dev.kaiwen.eventpulse.ticketing.TicketService;

import static org.assertj.core.api.Assertions.assertThat;
import dev.kaiwen.eventpulse.common.CanonicalJson;

/**
 * Ticket security: tokens only revealed to the owner, redemption is atomic
 * and single-use, repeat scans replay the original result, revoked tickets
 * are rejected, cross-owner scans are indistinguishable from missing, and the
 * cancel-vs-redeem race has exactly one winner.
 */
class TicketRedeemIT extends IntegrationTestBase {

    @Autowired
    private CommandDispatcher dispatcher;

    @Autowired
    private TicketService ticketService;

    private record Confirmed(OrganiserRef fixture, UserRef user, String bookingId, List<String> tokens) {
    }

    private Confirmed confirmedBooking() {
        OrganiserRef fixture = createEventWithTier(100, 10);
        UserRef user = createUser("USER");
        ResponseEntity<Map> created = post("/api/v1/bookings", user.token(),
                Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                        "quantity", 2, "ageConfirmed", true),
                CanonicalJson.newOpaqueToken());
        String bookingId = (String) body(created).get("id");
        post("/api/v1/bookings/" + bookingId + "/pay", user.token(), Map.of(), CanonicalJson.newOpaqueToken());
        dispatcher.tick();
        List<String> tokens = ticketService.revealTokens(user.id(), UUID.fromString(bookingId));
        assertThat(tokens).hasSize(2);
        return new Confirmed(fixture, user, bookingId, tokens);
    }

    @Test
    void redeemIsAtomicAndRepeatScanReturnsBusinessError() {
        Confirmed confirmed = confirmedBooking();
        UserRef organiserUser = createUser("ORGANISER");
        jdbc.update("UPDATE organisers SET owner_user_id = ? WHERE id = ?", organiserUser.id(),
                confirmed.fixture().organiserId());

        String token = confirmed.tokens().get(0);
        ResponseEntity<Map> first = post("/api/v1/organiser/tickets/redeem", organiserUser.token(),
                Map.of("token", token), CanonicalJson.newOpaqueToken());
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(body(first).get("result")).isEqualTo("OK");

        // Same token, different idempotency key: non-enumerable business error.
        ResponseEntity<Map> second = post("/api/v1/organiser/tickets/redeem", organiserUser.token(),
                Map.of("token", token), CanonicalJson.newOpaqueToken());
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(body(second).get("code")).isEqualTo("TICKET_NOT_REDEEMABLE");

        // usedAt recorded exactly once for this booking's ticket
        Integer usedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE booking_id = ? AND status = 'USED'", Integer.class,
                UUID.fromString(confirmed.bookingId()));
        assertThat(usedCount).isEqualTo(1);
    }

    @Test
    void redeemIsIdempotentUnderSameKey() {
        Confirmed confirmed = confirmedBooking();
        UserRef organiserUser = createUser("ORGANISER");
        jdbc.update("UPDATE organisers SET owner_user_id = ? WHERE id = ?", organiserUser.id(),
                confirmed.fixture().organiserId());
        String key = CanonicalJson.newOpaqueToken();
        String token = confirmed.tokens().get(0);

        ResponseEntity<Map> first = post("/api/v1/organiser/tickets/redeem", organiserUser.token(),
                Map.of("token", token), key);
        ResponseEntity<Map> replay = post("/api/v1/organiser/tickets/redeem", organiserUser.token(),
                Map.of("token", token), key);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(body(replay).get("ticketId")).isEqualTo(body(first).get("ticketId"));
    }

    @Test
    void crossOwnerRedeemIsHidden() {
        Confirmed confirmed = confirmedBooking();
        UserRef stranger = createUser("ORGANISER");
        ResponseEntity<Map> response = post("/api/v1/organiser/tickets/redeem", stranger.token(),
                Map.of("token", confirmed.tokens().get(0)), CanonicalJson.newOpaqueToken());
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        Integer used = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets t JOIN bookings b ON b.id = t.booking_id "
                        + "WHERE b.id = ? AND t.status = 'USED'", Integer.class,
                UUID.fromString(confirmed.bookingId()));
        assertThat(used).isZero();
    }

    @Test
    void redeemRequiresOrganiserRole() {
        Confirmed confirmed = confirmedBooking();
        // A plain USER — even the booking owner — lacks the ORGANISER role the
        // plan requires on redemption (§7.3/§8), so the role gate must reject
        // them before any ownership check runs.
        ResponseEntity<Map> response = post("/api/v1/organiser/tickets/redeem", confirmed.user().token(),
                Map.of("token", confirmed.tokens().get(0)), CanonicalJson.newOpaqueToken());
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        Integer used = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE booking_id = ? AND status = 'USED'", Integer.class,
                UUID.fromString(confirmed.bookingId()));
        assertThat(used).isZero();
    }

    @Test
    void cancelledTicketIsNotRedeemable() {
        Confirmed confirmed = confirmedBooking();
        UserRef organiserUser = createUser("ORGANISER");
        jdbc.update("UPDATE organisers SET owner_user_id = ? WHERE id = ?", organiserUser.id(),
                confirmed.fixture().organiserId());

        ResponseEntity<Map> cancelled = post("/api/v1/bookings/" + confirmed.bookingId() + "/cancel",
                confirmed.user().token(), Map.of("reason", "cannot attend"), CanonicalJson.newOpaqueToken());
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> redeem = post("/api/v1/organiser/tickets/redeem", organiserUser.token(),
                Map.of("token", confirmed.tokens().get(0)), CanonicalJson.newOpaqueToken());
        assertThat(redeem.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void cancelAndRedeemRaceHasExactlyOneWinner() throws Exception {
        Confirmed confirmed = confirmedBooking();
        UserRef organiserUser = createUser("ORGANISER");
        jdbc.update("UPDATE organisers SET owner_user_id = ? WHERE id = ?", organiserUser.id(),
                confirmed.fixture().organiserId());
        String token = confirmed.tokens().get(0);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<ResponseEntity<Map>> cancelFuture = pool.submit(
                () -> post("/api/v1/bookings/" + confirmed.bookingId() + "/cancel", confirmed.user().token(),
                        Map.of("reason", "race"), CanonicalJson.newOpaqueToken()));
        java.util.concurrent.Future<ResponseEntity<Map>> redeemFuture = pool.submit(
                () -> post("/api/v1/organiser/tickets/redeem", organiserUser.token(), Map.of("token", token),
                        CanonicalJson.newOpaqueToken()));
        ResponseEntity<Map> cancelResult = cancelFuture.get(60, java.util.concurrent.TimeUnit.SECONDS);
        ResponseEntity<Map> redeemResult = redeemFuture.get(60, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        String ticketStatus = jdbc.queryForObject("""
                SELECT t.status FROM tickets t JOIN bookings b ON b.id = t.booking_id
                WHERE b.id = ? ORDER BY t.sequence LIMIT 1
                """, String.class, UUID.fromString(confirmed.bookingId()));
        boolean redeemWon = redeemResult.getStatusCode().value() == 200;
        boolean cancelWon = cancelResult.getStatusCode().value() == 200;
        assertThat(redeemWon ^ cancelWon || (redeemWon && cancelWon && !ticketStatus.equals("USED")))
                .as("redeem=%s cancel=%s ticket=%s", redeemWon, cancelWon, ticketStatus)
                .isTrue();
        if (redeemWon) {
            assertThat(ticketStatus).isEqualTo("USED");
        }
        if (cancelWon && !redeemWon) {
            assertThat(ticketStatus).isEqualTo("REVOKED");
        }
    }
}
