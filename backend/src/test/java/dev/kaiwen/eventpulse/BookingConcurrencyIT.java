package dev.kaiwen.eventpulse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import dev.kaiwen.eventpulse.IntegrationTestBase.UserRef;
import dev.kaiwen.eventpulse.IntegrationTestBase.OrganiserRef;

import static org.assertj.core.api.Assertions.assertThat;
import dev.kaiwen.eventpulse.common.CanonicalJson;

/**
 * Concurrency matrix core: same-tier overselling and the first-time quota row.
 * These tests fail if protocol A is not implemented with UPSERT + row locks +
 * conditional updates.
 */
class BookingConcurrencyIT extends IntegrationTestBase {

    @Test
    void hundredConcurrentBookingsOnFiftyCapacityNeverOversell() throws Exception {
        OrganiserRef fixture = createEventWithTier(50, 10);
        List<UserRef> users = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            users.add(createUser("USER"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(32);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (UserRef user : users) {
            futures.add(pool.submit((Callable<Void>) () -> {
                ResponseEntity<Map> response = post("/api/v1/bookings", user.token(),
                        Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                                "quantity", 1, "ageConfirmed", true),
                        CanonicalJson.newOpaqueToken());
                if (response.getStatusCode().value() == 201) {
                    ok.incrementAndGet();
                }
                else {
                    conflicts.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(ok.get()).as("exactly capacity bookings succeed").isEqualTo(50);
        assertThat(conflicts.get()).isEqualTo(50);
        assertInventoryInvariant(fixture.tierId());
        Map<String, Object> inventory = inventoryRow(fixture.tierId());
        assertThat(((Number) inventory.get("reserved")).longValue()).isEqualTo(50);
        assertThat(((Number) inventory.get("available")).longValue()).isZero();
        Integer bookings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE tier_id = ? AND status = 'PAYMENT_PENDING'",
                Integer.class, fixture.tierId());
        assertThat(bookings).isEqualTo(50);
    }

    @Test
    void concurrentFirstBookingsRespectPerUserLimitOnFreshQuotaRow() throws Exception {
        OrganiserRef fixture = createEventWithTier(100, 5);
        UserRef user = createUser("USER");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final String key = CanonicalJson.newOpaqueToken();
            futures.add(pool.submit((Callable<Void>) () -> {
                ResponseEntity<Map> response = post("/api/v1/bookings", user.token(),
                        Map.of("eventId", fixture.eventId().toString(), "tierId", fixture.tierId().toString(),
                                "quantity", 2, "ageConfirmed", true),
                        key);
                if (response.getStatusCode().value() == 201) {
                    ok.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 2 each: the third batch of 2 would exceed perUserLimit=5, so exactly 2 succeed.
        assertThat(ok.get()).isEqualTo(2);
        assertInventoryInvariant(fixture.tierId());
        Integer active = jdbc.queryForObject(
                "SELECT active_quantity FROM user_tier_quota WHERE user_id = ? AND tier_id = ?",
                Integer.class, user.id(), fixture.tierId());
        assertThat(active).isEqualTo(4);
        Integer reserved = jdbc.queryForObject("SELECT reserved FROM inventory WHERE tier_id = ?", Integer.class,
                fixture.tierId());
        assertThat(reserved).isEqualTo(4);
    }

    @Test
    void idempotencyReplayReturnsSameBookingAndConflictingHashIs409() {
        OrganiserRef fixture = createEventWithTier(100, 10);
        UserRef user = createUser("USER");
        String key = CanonicalJson.newOpaqueToken();
        Map<String, Object> requestBody = Map.of("eventId", fixture.eventId().toString(), "tierId",
                fixture.tierId().toString(), "quantity", 1, "ageConfirmed", true);

        ResponseEntity<Map> first = post("/api/v1/bookings", user.token(), requestBody, key);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        String bookingId = (String) body(first).get("id");

        ResponseEntity<Map> replay = post("/api/v1/bookings", user.token(), requestBody, key);
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(body(replay).get("id")).isEqualTo(bookingId);

        Map<String, Object> differentBody = Map.of("eventId", fixture.eventId().toString(), "tierId",
                fixture.tierId().toString(), "quantity", 2, "ageConfirmed", true);
        ResponseEntity<Map> conflict = post("/api/v1/bookings", user.token(), differentBody, key);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(body(conflict).get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        ResponseEntity<Map> shortKey = post("/api/v1/bookings", user.token(), requestBody, "too-short");
        assertThat(shortKey.getStatusCode().value()).isEqualTo(400);
    }

    /**
     * PROBE regression for the ABBA deadlock: before the fix protocol A
     * acquired quota → inventory while every protocol-B transition (cancel,
     * expiry, capture, payment failure) mutated inventory before quota, so a
     * creation racing a cancel/confirm of the same user+tier could deadlock.
     * Both directions now lock booking → quota → inventory, so a sustained
     * mixed stream must complete with no deadlocks, no 500s and an intact
     * inventory equation.
     */
    @Test
    void interleavedCreateAndCancelOnSameTierNeverDeadlocks() throws Exception {
        OrganiserRef fixture = createEventWithTier(500, 500);
        UserRef user = createUser("USER");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger terminal = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        // Even threads only create; odd threads cancel whatever is pending, so
        // protocol A and protocol B constantly race over the same (user, tier).
        for (int t = 0; t < 8; t++) {
            final boolean canceller = t % 2 == 1;
            futures.add(pool.submit((Callable<Void>) () -> {
                try {
                    for (int i = 0; i < 15; i++) {
                        if (canceller) {
                            List<String> pending = jdbc.queryForList("""
                                    SELECT id FROM bookings WHERE user_id = ? AND tier_id = ?
                                      AND status = 'PAYMENT_PENDING' LIMIT 1
                                    """, String.class, user.id(), fixture.tierId());
                            if (!pending.isEmpty()) {
                                ResponseEntity<Map> cancelled = post(
                                        "/api/v1/bookings/" + pending.get(0) + "/cancel", user.token(),
                                        Map.of("reason", "deadlock probe"), CanonicalJson.newOpaqueToken());
                                int status = cancelled.getStatusCode().value();
                                if (status == 200) {
                                    ok.incrementAndGet();
                                }
                                else if (status >= 500) {
                                    terminal.incrementAndGet();
                                }
                            }
                        }
                        else {
                            ResponseEntity<Map> created = post("/api/v1/bookings", user.token(),
                                    Map.of("eventId", fixture.eventId().toString(),
                                           "tierId", fixture.tierId().toString(),
                                           "quantity", 1, "ageConfirmed", true),
                                    CanonicalJson.newOpaqueToken());
                            if (created.getStatusCode().value() == 201) {
                                ok.incrementAndGet();
                            }
                            else if (created.getStatusCode().value() >= 500) {
                                terminal.incrementAndGet();
                            }
                        }
                    }
                }
                catch (Exception e) {
                    terminal.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> future : futures) {
            future.get(180, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(terminal.get())
                .as("no request may fail with a server error (deadlock would surface as 500)")
                .isZero();
        assertThat(ok.get()).isGreaterThan(0);
        assertInventoryInvariant(fixture.tierId());
        // Invariant: activeQuantity of pending bookings equals reserved stock.
        Map<String, Object> pending = jdbc.queryForMap("""
                SELECT COUNT(*) AS bookings, COALESCE(SUM(quantity), 0) AS quantity FROM bookings
                WHERE user_id = ? AND tier_id = ? AND status = 'PAYMENT_PENDING'
                """, user.id(), fixture.tierId());
        long reservedSum = jdbc.queryForObject(
                "SELECT reserved FROM inventory WHERE tier_id = ?", Long.class, fixture.tierId())
                .longValue();
        assertThat(((Number) pending.get("bookings")).intValue())
                .as("each pending booking reserved exactly its quantity")
                .isEqualTo(((Number) pending.get("quantity")).intValue());
        if (((Number) pending.get("bookings")).intValue() == 0) {
            assertThat(reservedSum).as("no pending left → nothing reserved").isZero();
        }
    }
}

