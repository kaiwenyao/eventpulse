package dev.kaiwen.eventpulse;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 多个 Profile Wiring IT 共用一个 PostgreSQL 容器（一次性迁移、复用实例，
 * 避免每个上下文起一个容器拖慢测试）。Ryuk 被禁用，清理交给
 * `make testcontainers-cleanup`。
 */
final class SharedPostgres {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private SharedPostgres() {
    }
}
