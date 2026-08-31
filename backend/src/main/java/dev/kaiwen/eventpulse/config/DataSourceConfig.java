package dev.kaiwen.eventpulse.config;

import java.time.Duration;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resource bulkheads (plan §3.1 and §10.3): transactional writes, search /
 * vector reads and background batch tasks run on separate connection pools,
 * with separate database credentials (roles) configurable per pool, distinct
 * statement timeouts and bounded sizes.
 *
 * <ul>
 *   <li><b>tx</b> ({@code tx-pool}): the transactional write pool used by all
 *       booking/inventory/quota/payment business transactions. This is the
 *       primary DataSource that Flyway, {@code @Transactional} and the default
 *       JdbcTemplate bind to.</li>
 *   <li><b>search</b> ({@code search-pool}): read-only pool for catalogue
 *       search and recommendation candidate/scoring/display queries. The
 *       session statement timeout is short (2s default), connections are JVM
 *       read-only, and DB_SEARCH_USER/DB_SEARCH_PASSWORD may point to a
 *       PostgreSQL read-only role; they default to the primary credentials so
 *       local and test environments work unchanged.</li>
 *   <li><b>batch</b> ({@code batch-pool}): pool for the outbox relay, command
 *       dispatcher, expiry scan and the event-cancellation batch. A slow batch
 *       statement can never borrow a transactional connection (plan §17.3:
 *       queries must not starve transactions).</li>
 * </ul>
 *
 * <p>The primary beans are declared explicitly here because introducing any
 * custom DataSource bean disables Spring Boot's single-DataSource
 * auto-configuration.
 */
@org.springframework.context.annotation.Configuration
public class DataSourceConfig {

    /** Property-backed Hikari bootstrap with role/statement-timeout bulkheads. */
    private HikariDataSource hikari(String poolName, String url, String username, String password,
            int maxSize, long connectionTimeoutMs, long statementTimeoutMs, boolean readOnly) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxSize);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(connectionTimeoutMs);
        if (statementTimeoutMs > 0) {
            // Server-enforced statement budget for every statement of this pool.
            config.setConnectionInitSql("SET statement_timeout = " + statementTimeoutMs);
        }
        if (readOnly) {
            config.setReadOnly(true);
        }
        return new HikariDataSource(config);
    }

    @Bean(destroyMethod = "close")
    @Primary
    public DataSource txDataSource(
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}") String url,
            @org.springframework.beans.factory.annotation.Value(
                    "${spring.datasource.username}") String username,
            @org.springframework.beans.factory.annotation.Value(
                    "${spring.datasource.password}") String password,
            @org.springframework.beans.factory.annotation.Value(
                    "${spring.datasource.hikari.maximum-pool-size:20}") int maxSize,
            @org.springframework.beans.factory.annotation.Value(
                    "${spring.datasource.hikari.connection-timeout:5000}") long connectionTimeoutMs) {
        // Transactional writes keep the database default statement timeout; a
        // hard cap here would guess at legitimate business latencies.
        return hikari("tx-pool", url, username, password, maxSize, connectionTimeoutMs, -1, false);
    }

    @Bean(destroyMethod = "close")
    public DataSource searchDataSource(
            @org.springframework.beans.factory.annotation.Value(
                    "${spring.datasource.url}") String url,
            @org.springframework.beans.factory.annotation.Value(
                    "${DB_SEARCH_USER:${spring.datasource.username}}") String username,
            @org.springframework.beans.factory.annotation.Value(
                    "${DB_SEARCH_PASSWORD:${spring.datasource.password}}") String password,
            @org.springframework.beans.factory.annotation.Value(
                    "${eventpulse.pools.search.max-size:8}") int maxSize,
            @org.springframework.beans.factory.annotation.Value(
                    "${eventpulse.pools.search.connection-timeout-ms:3000}") long connectionTimeoutMs,
            @org.springframework.beans.factory.annotation.Value(
                    "${eventpulse.pools.search.statement-timeout-ms:2000}") long statementTimeoutMs) {
        return hikari("search-pool", url, username, password, maxSize, connectionTimeoutMs,
                statementTimeoutMs, true);
    }

    @Bean(destroyMethod = "close")
    public DataSource batchDataSource(
            @org.springframework.beans.factory.annotation.Value(
                    "${spring.datasource.url}") String url,
            @org.springframework.beans.factory.annotation.Value(
                    "${DB_BATCH_USER:${spring.datasource.username}}") String username,
            @org.springframework.beans.factory.annotation.Value(
                    "${DB_BATCH_PASSWORD:${spring.datasource.password}}") String password,
            @org.springframework.beans.factory.annotation.Value(
                    "${eventpulse.pools.batch.max-size:8}") int maxSize,
            @org.springframework.beans.factory.annotation.Value(
                    "${eventpulse.pools.batch.connection-timeout-ms:10000}") long connectionTimeoutMs,
            @org.springframework.beans.factory.annotation.Value(
                    "${eventpulse.pools.batch.statement-timeout-ms:30000}") long statementTimeoutMs) {
        return hikari("batch-pool", url, username, password, maxSize, connectionTimeoutMs,
                statementTimeoutMs, false);
    }

    // -------------------------------------------------------- jdbc templates

    @Bean
    @Primary
    public JdbcTemplate txJdbcTemplate(@Qualifier("txDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** Read-only search/vector pool with a short statement timeout (§10.3). */
    @Bean
    public JdbcTemplate searchJdbcTemplate(@Qualifier("searchDataSource") DataSource dataSource) {
        // The pool's session statement_timeout is the server-side budget;
        // setQueryTimeout adds the client-side cancellation path.
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(2);
        return template;
    }

    @Bean
    public JdbcTemplate batchJdbcTemplate(@Qualifier("batchDataSource") DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(30);
        return template;
    }

    // --------------------------------------------------- transaction wiring

    @Bean
    @Primary
    public PlatformTransactionManager txTransactionManager(
            @Qualifier("txDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public PlatformTransactionManager batchTransactionManager(
            @Qualifier("batchDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    @Primary
    public TransactionTemplate transactionTemplate(
            @Qualifier("txTransactionManager") PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public TransactionTemplate batchTransactionTemplate(
            @Qualifier("batchTransactionManager") PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}