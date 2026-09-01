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
        }

        AtomicInteger accepted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(() -> {
                try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                        PreparedStatement update = connection.prepareStatement("""
                                UPDATE events SET sold = sold + 1, updated_at = now()
                                WHERE id = 1 AND status = 'PUBLISHED' AND sold + 1 <= capacity
                                """)) {
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

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                ResultSet rs = connection.createStatement().executeQuery("SELECT sold FROM events WHERE id = 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(10);
        }
        assertThat(accepted.get()).isEqualTo(10);
    }
}
