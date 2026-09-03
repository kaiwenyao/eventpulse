package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.PageResult;
import dev.kaiwen.eventpulse.dto.WalletDtos.LedgerVo;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.service.BookingService;
import dev.kaiwen.eventpulse.service.WalletService;

/**
 * 旧余额接入流水体系（真实 PostgreSQL + 两阶段 Flyway）：
 * 先只迁移到 V1 并写入「迁移前」的老账户与老订单，再让应用把迁移跑完（V2）。
 * 验证：期初记录不改变余额、余额可从期初 + 后续流水核对、
 * 迁移前创建的老订单（没有旧扣款流水）之后仍能正常退款记账。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.profiles.active=api")
@Testcontainers(disabledWithoutDocker = true)
class WalletLedgerMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static {
        // 类加载时手工启动：静态块要赶在 Spring 上下文之前写入「迁移前」数据。
        POSTGRES.start();
        // 阶段一：只跑到 V1，制造「流水体系启用之前」的旧数据。
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("1")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            long organiser;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO users (email, password, name, role, wallet_cents)
                    VALUES ('legacy-organiser@it.dev', 'x', 'O', 'ORGANISER', 0)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    organiser = keys.getLong(1);
                }
            }
            // legacy-with-balance：只有余额、没有任何订单
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO users (email, password, name, role, wallet_cents)
                    VALUES ('legacy-balance@it.dev', 'x', 'B', 'USER', 7777)
                    """)) {
                insert.executeUpdate();
            }
            // legacy-buyer：余额 500，有一笔迁移前的已确认订单（paid 300）
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO users (email, password, name, role, wallet_cents)
                    VALUES ('legacy-buyer@it.dev', 'x', 'U', 'USER', 500)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    long buyer = keys.getLong(1);
                    try (PreparedStatement booking = connection.prepareStatement("""
                            INSERT INTO bookings (user_id, event_id, quantity, paid_cents, status, created_at)
                            VALUES (?, ?, 1, 300, 'CONFIRMED', ?)
                            """)) {
                        booking.setLong(1, buyer);
                        booking.setLong(2, legacyEvent(connection, organiser));
                        booking.setTimestamp(3, Timestamp.from(Instant.now().minusSeconds(86_400)));
                        booking.executeUpdate();
                    }
                }
            }
        }
        catch (Exception e) {
            throw new IllegalStateException("无法写入迁移前旧数据", e);
        }
    }

    private static long legacyEvent(Connection connection, long organiser) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO events (title, description, category, city, starts_at, ends_at, price_cents,
                    capacity, sold, organiser_id, status, created_at, updated_at, max_quantity_per_booking)
                VALUES ('迁移前的活动', 'd', 'music', 'Berlin', ?, ?, 300, 10, 1, ?, 'PUBLISHED', now(), now(), 10)
                """, Statement.RETURN_GENERATED_KEYS)) {
            Instant start = Instant.now().plusSeconds(7 * 86_400);
            insert.setTimestamp(1, Timestamp.from(start));
            insert.setTimestamp(2, Timestamp.from(start.plusSeconds(3600)));
            insert.setLong(3, organiser);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> POSTGRES.getPassword());
        registry.add("eventpulse.redis-enabled", () -> "false");
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    BookingService bookings;
    @Autowired
    EventRepository events;
    @Autowired
    WalletService wallets;

    @Test
    void migrationWritesOpeningRowsWithoutChangingBalancesAndOldBookingsStayRefundable() {
        Long balanceUser = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = 'legacy-balance@it.dev'", Long.class);
        Long buyer = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = 'legacy-buyer@it.dev'", Long.class);

        // V2 已由应用启动时执行：期初记录存在且金额等于迁移时余额，余额未被改动
        Long openingBalance = jdbc.queryForObject("""
                SELECT amount_cents FROM wallet_ledger
                WHERE user_id = ? AND biz_type = 'OPENING_BALANCE'
                """, Long.class, balanceUser);
        assertThat(openingBalance).isEqualTo(7777L);
        assertThat(jdbc.queryForObject(
                "SELECT wallet_cents FROM users WHERE id = ?", Long.class, balanceUser)).isEqualTo(7777L);
        assertThat(jdbc.queryForObject(
                "SELECT ledger_seq FROM users WHERE id = ?", Long.class, balanceUser)).isEqualTo(1L);

        // 新注册的零余额用户不产生期初记录
        Long zeroRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_ledger l
                JOIN users u ON u.id = l.user_id
                WHERE u.email = 'legacy-organiser@it.dev'
                """, Long.class);
        assertThat(zeroRows).isZero();

        // 老订单（没有旧扣款流水）迁移后取消 → 正常退款并记账
        Event event = events.findByOrganiserIdOrderByStartsAtDesc(
                jdbc.queryForObject("SELECT id FROM users WHERE email = 'legacy-organiser@it.dev'", Long.class))
                .get(0);
        BaseContext.setUserId(buyer);
        BaseContext.setRole("USER");
        Long bookingId = jdbc.queryForObject(
                "SELECT id FROM bookings WHERE user_id = ?", Long.class, buyer);
        bookings.cancel(bookingId);
        BaseContext.clear();

        assertThat(jdbc.queryForObject(
                "SELECT wallet_cents FROM users WHERE id = ?", Long.class, buyer)).isEqualTo(800L);
        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT biz_type, amount_cents, balance_before_cents, balance_after_cents, seq_no "
                        + "FROM wallet_ledger WHERE user_id = ? ORDER BY seq_no", buyer);
        // 期初(500) + 退款(+300)：没有伪造的旧扣款流水
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(0).get("biz_type")).isEqualTo("OPENING_BALANCE");
        assertThat(ledger.get(1).get("biz_type")).isEqualTo("BOOKING_REFUND");
        long before = ((Number) ledger.get(1).get("balance_before_cents")).longValue();
        long after = ((Number) ledger.get(1).get("balance_after_cents")).longValue();
        assertThat(before).isEqualTo(500L);
        assertThat(after).isEqualTo(800L);
        assertThat(before + 300).isEqualTo(after);

        // 余额明细分页与类型过滤（WalletService.ledger）
        loginAs(buyer);
        PageResult<LedgerVo> page = wallets.ledger(buyer, null, null, null, 0, 1);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords()).hasSize(1);
        PageResult<LedgerVo> refunds = wallets.ledger(buyer, "BOOKING_REFUND", null, null, 0, 10);
        assertThat(refunds.getTotal()).isEqualTo(1);
        assertThat(refunds.getRecords().get(0).bookingId()).isEqualTo(bookingId);
        PageResult<LedgerVo> recharges = wallets.ledger(buyer, "RECHARGE", null, null, 0, 10);
        assertThat(recharges.getTotal()).isZero();
        BaseContext.clear();
    }

    private void loginAs(Long userId) {
        BaseContext.setUserId(userId);
        BaseContext.setRole("USER");
    }

    @org.junit.jupiter.api.Test
    void historicalLedgerInsertAcceptsInstantCreatedAt() {
        // Seeder 走 recordLedgerOnly 直插历史流水（带 createdAt）：锁住这条真实 SQL。
        String email = "it-ledger-direct-" + System.nanoTime() + "@test.dev";
        jdbc.update("INSERT INTO users (email, password, name, role, wallet_cents) "
                + "VALUES (?, 'x', 'L', 'USER', 0)", email);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        wallets.recordLedgerOnly(userId, 88800, dev.kaiwen.eventpulse.entity.WalletLedger.TYPE_OPENING_BALANCE,
                "OPENING_BALANCE:direct-" + userId, null, null,
                "direct historical insert", 0, 88800, 1, Instant.now());
        java.sql.Timestamp createdAt = jdbc.queryForObject(
                "SELECT created_at FROM wallet_ledger WHERE external_biz_id = ?",
                java.sql.Timestamp.class, "OPENING_BALANCE:direct-" + userId);
        assertThat(createdAt).isNotNull();
    }
}
