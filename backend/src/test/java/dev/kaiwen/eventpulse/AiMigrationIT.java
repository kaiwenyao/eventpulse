package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V9 迁移的两条路径：
 * 1. 全新建库：V1..V9 一次跑完，AI 表存在、旧推荐表不存在。
 * 2. 已执行过旧 migration 的库升级：V1..V8 后 recommendation_requests 里有
 *    数据，再迁到 V9 时表被删除、数据清空 —— 不回改历史迁移。
 */
@Testcontainers(disabledWithoutDocker = true)
class AiMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static Flyway flyway(String database, String target) {
        String url = POSTGRES.getJdbcUrl()
                .replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static boolean tableExists(String url, String table) throws Exception {
        try (Connection conn = java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    void freshDatabaseEndsWithoutLegacyTableAndWithAiTables() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(tableExists(POSTGRES.getJdbcUrl(), "recommendation_requests")).isFalse();
        assertThat(tableExists(POSTGRES.getJdbcUrl(), "ai_conversations")).isTrue();
        assertThat(tableExists(POSTGRES.getJdbcUrl(), "ai_messages")).isTrue();
        assertThat(tableExists(POSTGRES.getJdbcUrl(), "ai_requests")).isTrue();
    }

    @Test
    void upgradePathDropsLegacyTableWithData() throws Exception {
        // 独立数据库模拟“执行过 V1..V8 的旧环境”。
        String url = POSTGRES.getJdbcUrl()
                .replace("/" + POSTGRES.getDatabaseName(), "/postgres");
        try (Connection conn = java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS upgrade_db WITH (FORCE)");
            st.execute("CREATE DATABASE upgrade_db");
        }
        String legacyUrl = POSTGRES.getJdbcUrl()
                .replace("/" + POSTGRES.getDatabaseName(), "/upgrade_db");

        flyway("upgrade_db", "8").migrate();
        assertThat(tableExists(legacyUrl, "recommendation_requests")).isTrue();

        // 旧环境里真实的遗留数据：升级后必须连同表一起消失。
        try (Connection conn = java.sql.DriverManager.getConnection(legacyUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO recommendation_requests
                            (request_id, user_id, partition_key, model_version, feature_version,
                             frozen_candidates, queried_at, expires_at)
                        VALUES ('legacy-1', 1, 'default', 'rules-v1', 'pref-v1', '1,2', ?, ?)
                        """)) {
            ps.setTimestamp(1, java.sql.Timestamp.from(Instant.now()));
            ps.setTimestamp(2, java.sql.Timestamp.from(Instant.now().plusSeconds(300)));
            ps.executeUpdate();
        }

        flyway("upgrade_db", "9").migrate();
        assertThat(tableExists(legacyUrl, "recommendation_requests")).isFalse();
        assertThat(tableExists(legacyUrl, "ai_conversations")).isTrue();
        assertThat(tableExists(legacyUrl, "ai_messages")).isTrue();
        assertThat(tableExists(legacyUrl, "ai_requests")).isTrue();
    }
}
