package com.eventpulse;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.eventpulse.common.AppProperties;
import com.eventpulse.payment.SimulatedPaymentGateway;
import com.eventpulse.payment.SimulatedPaymentGateway.GatewayResult;
import com.eventpulse.payment.SimulatedPaymentGateway.Outcome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Gateway simulator: scenario branches, idempotent replay, void semantics. */
class SimulatedPaymentGatewayTest {

    private JdbcTemplate jdbc;
    private SimulatedPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        AppProperties properties = new AppProperties(
                new AppProperties.Security("s", "p", Duration.ofMinutes(1), Duration.ofDays(1),
                        Duration.ofMinutes(10), List.of()),
                null, null, null,
                new AppProperties.Gateway("pi-force-fail:FAILURE:0;pi-late:LATE_SUCCESS:2", Duration.ZERO),
                null, null);
        gateway = new SimulatedPaymentGateway(jdbc, properties);
    }

    private void noStoredResult() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of());
    }

    @Test
    void captureScenarioRulesAreHonoured() {
        noStoredResult();
        assertThat(gateway.capture("pi-force-fail-123", 1000L).outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(gateway.capture("pi-late-456", 1000L).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(gateway.capture("pi-normal-789", 1000L).outcome()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    void storedResultIsReplayedForSameProviderKey() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("SUCCEEDED"))
                .thenReturn(List.of("FAILED"))
                .thenReturn(List.of("PENDING"));
        assertThat(gateway.capture("pi-a", 1L).outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(gateway.refund("rf-a", "pi-a", 1L).outcome()).isEqualTo(Outcome.FAILURE);
        assertThat(gateway.refund("rf-b", "pi-a", 1L).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void voidSemanticsByCaptureState() {
        // one queryForList per voidCharge call: empty / PENDING / FAILED / SUCCEEDED
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of())
                .thenReturn(List.of("PENDING"))
                .thenReturn(List.of("FAILED"))
                .thenReturn(List.of("SUCCEEDED"));

        // capture never reached the gateway
        assertThat(gateway.voidCharge("vd-1", "pi-x").detail()).contains("capture_not_found");
        // pending -> voided
        assertThat(gateway.voidCharge("vd-2", "pi-y").detail()).contains("pending_capture_voided");
        // failed -> nothing to void
        assertThat(gateway.voidCharge("vd-3", "pi-z").detail()).contains("failed_capture_voided");
        // already captured -> must be refunded
        assertThat(gateway.voidCharge("vd-4", "pi-w")).isEqualTo(new GatewayResult(Outcome.UNKNOWN,
                "ALREADY_CAPTURED"));
    }

    @Test
    void queryStatusResolvesOnlyPastAvailableAt() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of("SUCCEEDED"))
                .thenReturn(List.of())
                .thenReturn(List.of("FAILED"));
        assertThat(gateway.queryStatus("k1").outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(gateway.queryStatus("k2").outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(gateway.queryStatus("k3").outcome()).isEqualTo(Outcome.FAILURE);
    }

    @Test
    void capturedAmountDefaultsToZeroWhenMissing() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
        assertThat(gateway.capturedAmount("missing")).isZero();
    }
}
