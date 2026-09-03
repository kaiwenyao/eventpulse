package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 基线迁移的验收：V1__init.sql 之后按版本号顺序追加 V2（购物车 / 钱包流水 /
 * 结算幂等键），空库跑完之后每张实体表都在，旧版规则推荐的残留表不在。
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
        assertThat(result.migrationsExecuted).isEqualTo(2);
        for (String table : EXPECTED_TABLES) {
            assertThat(tableExists(table)).as(table).isTrue();
        }
        // 旧版规则推荐的专用表已随合并一起消失，不该再被建出来。
        assertThat(tableExists("recommendation_requests")).isFalse();
    }
}
