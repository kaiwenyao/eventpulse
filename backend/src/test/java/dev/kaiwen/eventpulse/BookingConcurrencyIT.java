package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Concurrent inventory: 20 threads each try to book 1 of 10 seats.
 * The native {@code sold + qty <= capacity} update must refuse oversell.
 */
@Testcontainers(disabledWithoutDocker = true)
class BookingConcurrencyIT {

    @Container
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void incrementSoldDoesNotOversell() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.createStatement().execute("""
                    INSERT INTO users (email, password, name, role)
                    VALUES ('c@t.dev', 'x', 'C', 'ORGANISER')
                    """);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO events (title, description, category, city, starts_at, ends_at, price_cents,
                        capacity, sold, organiser_id, status, created_at, updated_at, max_quantity_per_booking)
                    VALUES ('并发', 'd', 'music', '上海', ?, ?, 100, 10, 0, 1, 'PUBLISHED', now(), now(), 10)
                    """)) {
                Instant start = Instant.now().plusSeconds(86400);
                insert.setTimestamp(1, Timestamp.from(start));
                insert.setTimestamp(2, Timestamp.from(start.plusSeconds(3600)));
                insert.executeUpdate();
            }
            // 记录本测试创建的事件 id（容器会被两个测试共用，不能用写死的 id=1）。
            long eventId = 0;
            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT id FROM events WHERE title = '并发'")) {
                assertThat(rs.next()).isTrue();
                eventId = rs.getLong(1);
            }
            long targetId = eventId;

            AtomicInteger accepted = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(20);
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                jobs.add(() -> {
                    try (Connection conn = DriverManager.getConnection(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                            PreparedStatement update = conn.prepareStatement("""
                                    UPDATE events SET sold = sold + 1, updated_at = now()
                                    WHERE id = ? AND status = 'PUBLISHED' AND sold + 1 <= capacity
                                    """)) {
                        update.setLong(1, targetId);
                        int rows = update.executeUpdate();
                        if (rows == 1) {
                            accepted.incrementAndGet();
                        }
                        return rows;
                    }
                });
            }
            List<Future<Integer>> futures = pool.invokeAll(jobs);
            for (Future<Integer> future : futures) {
                future.get();
            }
            pool.shutdown();

            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT sold FROM events WHERE id = " + targetId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(10);
            }
            assertThat(accepted.get()).isEqualTo(10);
        }
    }

    @Test
    void failedWalletDebitRollsBackInventoryReservation() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        long organiserId;
        long customerId;
        long eventId;
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.createStatement().execute("""
                    INSERT INTO users (email, password, name, role)
                    VALUES ('rollback-organiser@t.dev', 'x', 'Organiser', 'ORGANISER'),
                           ('rollback-customer@t.dev', 'x', 'Customer', 'USER')
                    """);
            try (ResultSet rs = connection.createStatement().executeQuery("""
                    SELECT id, email FROM users
                    WHERE email IN ('rollback-organiser@t.dev', 'rollback-customer@t.dev')
                    """)) {
                assertThat(rs.next()).isTrue();
                long firstId = rs.getLong("id");
                String firstEmail = rs.getString("email");
                assertThat(rs.next()).isTrue();
                long secondId = rs.getLong("id");
                String secondEmail = rs.getString("email");
                organiserId = "rollback-organiser@t.dev".equals(firstEmail) ? firstId : secondId;
                customerId = "rollback-customer@t.dev".equals(firstEmail) ? firstId : secondId;
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO events (title, description, category, city, starts_at, ends_at, price_cents,
                        capacity, sold, organiser_id, status, created_at, updated_at, max_quantity_per_booking)
                    VALUES ('余额回滚', 'd', 'music', '上海', ?, ?, 100, 10, 0, ?, 'PUBLISHED', now(), now(), 10)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                Instant start = Instant.now().plusSeconds(86400);
                insert.setTimestamp(1, Timestamp.from(start));
                insert.setTimestamp(2, Timestamp.from(start.plusSeconds(3600)));
                insert.setLong(3, organiserId);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    eventId = keys.getLong(1);
                }
            }

            connection.setAutoCommit(false);
            try (PreparedStatement reserve = connection.prepareStatement("""
                    UPDATE events
                    SET sold = sold + 1
                    WHERE id = ? AND status = 'PUBLISHED' AND sold + 1 <= capacity
                    """);
                    PreparedStatement debit = connection.prepareStatement("""
                            UPDATE users
                            SET wallet_cents = wallet_cents - 100
                            WHERE id = ? AND wallet_cents >= 100
                            """)) {
                reserve.setLong(1, eventId);
                assertThat(reserve.executeUpdate()).isEqualTo(1);
                debit.setLong(1, customerId);
                assertThat(debit.executeUpdate()).isZero();
                connection.rollback();
            }

            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT sold FROM events WHERE id = " + eventId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    void concurrentWalletDebitsNeverOverdraw() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        long userId;
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.createStatement().execute("""
                    INSERT INTO users (email, password, name, role, wallet_cents)
                    VALUES ('wallet-concurrency@t.dev', 'x', 'Wallet', 'USER', 1000)
                    """);
            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT id FROM users WHERE email = 'wallet-concurrency@t.dev'")) {
                assertThat(rs.next()).isTrue();
                userId = rs.getLong(1);
            }
        }

        AtomicInteger accepted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(() -> {
                try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                        PreparedStatement debit = connection.prepareStatement("""
                                UPDATE users
                                SET wallet_cents = wallet_cents - 100
                                WHERE id = ? AND wallet_cents >= 100
                                """)) {
                    debit.setLong(1, userId);
                    int rows = debit.executeUpdate();
                    if (rows == 1) {
                        accepted.incrementAndGet();
                    }
                    return rows;
                }
            });
        }
        for (Future<Integer> future : pool.invokeAll(jobs)) {
            future.get();
        }
        pool.shutdown();

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                ResultSet rs = connection.createStatement().executeQuery(
                        "SELECT wallet_cents FROM users WHERE id = " + userId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isZero();
        }
        assertThat(accepted.get()).isEqualTo(10);
    }

    @Test
    void concurrentWalletRechargesRespectBalanceCap() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        long userId;
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.createStatement().execute("""
                    INSERT INTO users (email, password, name, role, wallet_cents)
                    VALUES ('wallet-recharge@t.dev', 'x', 'Recharge', 'USER', 1000)
                    """);
            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT id FROM users WHERE email = 'wallet-recharge@t.dev'")) {
                assertThat(rs.next()).isTrue();
                userId = rs.getLong(1);
            }
        }

        AtomicInteger accepted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(() -> {
                try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                        PreparedStatement recharge = connection.prepareStatement("""
                                UPDATE users
                                SET wallet_cents = wallet_cents + 100
                                WHERE id = ? AND wallet_cents <= 2000 - 100
                                """)) {
                    recharge.setLong(1, userId);
                    int rows = recharge.executeUpdate();
                    if (rows == 1) {
                        accepted.incrementAndGet();
                    }
                    return rows;
                }
            });
        }
        for (Future<Integer> future : pool.invokeAll(jobs)) {
            future.get();
        }
        pool.shutdown();

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                ResultSet rs = connection.createStatement().executeQuery(
                        "SELECT wallet_cents FROM users WHERE id = " + userId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(2000);
        }
        assertThat(accepted.get()).isEqualTo(10);
    }

    @Test
    void concurrentMetricIncrementsNeverLoseUpdates() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        long targetId;
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.createStatement().execute("""
                    INSERT INTO users (email, password, name, role)
                    VALUES ('m@t.dev', 'x', 'M', 'ORGANISER')
                    """);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO events (title, description, category, city, starts_at, ends_at, price_cents,
                        capacity, sold, organiser_id, status, created_at, updated_at, max_quantity_per_booking)
                    VALUES ('统计并发', 'd', 'music', '上海', ?, ?, 100, 100, 0, 1, 'PUBLISHED', now(), now(), 100)
                    """)) {
                Instant start = Instant.now().plusSeconds(86400);
                insert.setTimestamp(1, Timestamp.from(start));
                insert.setTimestamp(2, Timestamp.from(start.plusSeconds(3600)));
                insert.executeUpdate();
            }
            // 记录本测试创建的事件 id（容器会被两个测试共用）。
            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT id FROM events WHERE title = '统计并发'")) {
                assertThat(rs.next()).isTrue();
                targetId = rs.getLong(1);
            }
        }

        // 20 个并发线程各自执行一次「原子加一」（INSERT ... ON CONFLICT DO UPDATE），
        // 对应 Kafka 同时到达的 20 条 BOOKING_CREATED 消息处理同一活动。
        // 每张订单张数各不相同（1..20），验证 tickets 按实际张数累加、bookings 按订单数累加。
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int qty = i + 1;
            jobs.add(() -> {
                try (Connection conn = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                        PreparedStatement upsert = conn.prepareStatement("""
                                INSERT INTO event_daily_metrics
                                    (event_id, metric_date, views, clicks, saves, unsaves,
                                     bookings, tickets, cancels, check_ins)
                                VALUES (?, CURRENT_DATE, 0, 0, 0, 0, 1, ?, 0, 0)
                                ON CONFLICT (event_id, metric_date) DO UPDATE
                                  SET bookings = event_daily_metrics.bookings + 1,
                                      tickets  = event_daily_metrics.tickets  + ?
                                """)) {
                    upsert.setLong(1, targetId);
                    upsert.setInt(2, qty);
                    upsert.setInt(3, qty);
                    return upsert.executeUpdate();
                }
            });
        }
        List<Future<Integer>> futures = pool.invokeAll(jobs);
        for (Future<Integer> future : futures) {
            future.get();
        }
        pool.shutdown();

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                ResultSet rs = connection.createStatement().executeQuery(
                        "SELECT bookings, tickets FROM event_daily_metrics WHERE event_id = " + targetId)) {
            assertThat(rs.next()).isTrue();
            // bookings 按订单数：20 次并发各 +1 = 20（首日 INSERT 已带 1，冲突则 +1）。
            assertThat(rs.getInt(1)).isEqualTo(20);
            // tickets 按实际张数累加：1+2+...+20 = 210。
            assertThat(rs.getInt(2)).isEqualTo(210);
        }
    }
}
