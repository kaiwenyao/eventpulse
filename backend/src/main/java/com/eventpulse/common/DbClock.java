package com.eventpulse.common;

import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The database clock is the single source of truth for transaction deadlines.
 * Application nodes only render time. Must be called inside the transaction
 * that consumes the value.
 */
@Component
public class DbClock {

    private final JdbcTemplate jdbc;

    public DbClock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Instant now() {
        return jdbc.queryForObject("SELECT now()", java.time.OffsetDateTime.class).toInstant();
    }
}
