package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 基线迁移的验收：V1__init.sql 之后按版本号顺序追加 V2（购物车 / 钱包流水 /
 * 结算幂等键）、V3（购物车排序索引）、V4（活动分类白名单），空库跑完之后每张
 * 实体表都在，旧版规则推荐的残留表不在，分类的 CHECK 约束也已生效。
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaBaselineIT {

    /** 与 JPA 实体一一对应的业务表；少一张就说明基线迁移漏了。 */
    private static final List<String> EXPECTED_TABLES = List.of(
            "users", "events", "bookings", "notifications", "event_audit_logs",
            "event_favourites", "tickets", "media_assets", "user_preferences",
            "interactions", "outbox", "consumed_events", "event_daily_metrics",
            "seed_runs", "ai_conversations", "ai_messages", "ai_requests",
            "cart_items", "checkouts", "wallet_ledger");

    /** 分类 CHECK 约束用例独占的 schema，避免和「空库迁移」用例抢 public。 */
    private static final String CATEGORY_CHECK_SCHEMA = "category_check";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static boolean tableExists(String table) throws Exception {
        try (Connection conn = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    void freshDatabaseGetsEveryTableFromTheSingleBaselineMigration() throws Exception {
        // Act
        var result = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // Assert
        assertThat(result.migrationsExecuted).isEqualTo(4);
        for (String table : EXPECTED_TABLES) {
            assertThat(tableExists(table)).as(table).isTrue();
        }
        // 旧版规则推荐的专用表已随合并一起消失，不该再被建出来。
        assertThat(tableExists("recommendation_requests")).isFalse();
    }

    /**
     * V4 的 CHECK 约束是分类白名单的最后一道防线：应用层校验只挡得住走 API 的写入，
     * seeder、SQL 脚本、psql 直连都绕得过去，脏分类一旦落库就永远搜不出来。
     */
    @Test
    void databaseRejectsACategoryOutsideTheWhitelist() throws Exception {
        // 迁到自己的 schema：上面那条用例断言的是「空库跑出 4 条迁移」，共用 public
        // 就变成谁先跑谁赢。两条用例因此互不干扰，与执行顺序无关。
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(CATEGORY_CHECK_SCHEMA)
                .load()
                .migrate();

        try (Connection conn = connect(CATEGORY_CHECK_SCHEMA)) {
            long organiser = insertOrganiser(conn);

            assertThatThrownBy(() -> insertEvent(conn, organiser, "工作坊"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("events_category_check");

            assertThatCode(() -> insertEvent(conn, organiser, "community")).doesNotThrowAnyException();
        }
    }

    private static Connection connect(String schema) throws SQLException {
        Connection conn = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // 用 SET search_path 而不是往 JDBC URL 上拼 currentSchema：URL 里已经带了
        // 查询串，拼接要靠 ? 还是 & 取决于容器实现，没必要赌。
        try (Statement statement = conn.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return conn;
    }

    private static long insertOrganiser(Connection conn) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO users (email, password, name, role) VALUES (?, 'x', 'Cat Check', 'ORGANISER')",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, "category-check-" + System.nanoTime() + "@eventpulse.dev");
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void insertEvent(Connection conn, long organiser, String category) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO events (title, description, category, city, starts_at, ends_at,
                    price_cents, capacity, sold, organiser_id, status)
                VALUES ('Category check', 'd', ?, 'Berlin', ?, ?, 100, 10, 0, ?, 'PUBLISHED')
                """)) {
            Instant start = Instant.now().plusSeconds(7 * 86_400);
            insert.setString(1, category);
            insert.setTimestamp(2, Timestamp.from(start));
            insert.setTimestamp(3, Timestamp.from(start.plusSeconds(3600)));
            insert.setLong(4, organiser);
            insert.executeUpdate();
        }
    }
}
