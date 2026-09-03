package dev.kaiwen.eventpulse;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 多个 Profile Wiring IT 共用一个 PostgreSQL 容器（一次性迁移、复用实例，
 * 避免每个上下文起一个容器拖慢测试）。Ryuk 被禁用，清理交给
 * `make testcontainers-cleanup`。
 *
 * 每个 Spring 测试上下文都持有自己的 Hikari 池（默认 max 10）并在 JVM
 * 生命周期内被缓存：11 个 IT 类 × 10 已超过 Postgres 默认
 * max_connections=100，全量跑会偶发 "FATAL: sorry, too many clients
 * already"，所以放宽到 300。
 */
final class SharedPostgres {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withCommand("postgres", "-c", "max_connections=300");

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
