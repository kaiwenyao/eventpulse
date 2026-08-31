package dev.kaiwen.eventpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.kaiwen.eventpulse.observability.BusinessMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BusinessMetricsTest {

    @Test
    void registersBusinessGaugesAndTicketCounters() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Number.class))).thenReturn(0L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry, jdbc);

        metrics.ticketRedeemAttempt();
        metrics.ticketRedeemRejected();
        metrics.refresh();

        assertThat(registry.get("eventpulse.outbox.oldest.pending.seconds").gauge().value()).isZero();
        assertThat(registry.get("eventpulse.commands.manual.review").gauge().value()).isZero();
        assertThat(registry.get("eventpulse.ticket.redeem.attempts").counter().count()).isEqualTo(1);
        assertThat(registry.get("eventpulse.ticket.redeem.rejections").counter().count()).isEqualTo(1);
    }
}
