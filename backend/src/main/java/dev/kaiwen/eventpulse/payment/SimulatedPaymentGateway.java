package dev.kaiwen.eventpulse.payment;

import java.time.Instant;
import java.util.List;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.error.ApiException;
import dev.kaiwen.eventpulse.common.error.ErrorCode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Isolated payment gateway simulator. Results are persisted so that status
 * queries survive restarts and UNKNOWN can be resolved deterministically.
 * Scenario selection comes exclusively from server-side configuration rules
 * (matched by provider-key prefix); the 'prod' profile refuses to start with
 * a non-empty rule set, so no user-controllable outcome can exist there.
 */
@Component
public class SimulatedPaymentGateway {

    public enum Outcome {
        SUCCESS, FAILURE, UNKNOWN
    }

    public record GatewayResult(Outcome outcome, String detail) {
    }

    private final JdbcTemplate jdbc;
    private final AppProperties properties;

    public SimulatedPaymentGateway(JdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public GatewayResult capture(String providerKey, long amountMinor) {
        String scenario = scenarioFor(providerKey);
        return resolve(providerKey, "CAPTURE", amountMinor, scenario);
    }

    public GatewayResult refund(String providerKey, String targetCaptureKey, long amountMinor) {
        return resolve(providerKey, "REFUND", amountMinor, scenarioFor(providerKey));
    }

    /**
     * Void a capture. A pending (uncaptured) charge is voided; an already
     * captured charge reports ALREADY_CAPTURED so the caller converts to a
     * refund compensation.
     */
    public GatewayResult voidCharge(String voidKey, String targetCaptureKey) {
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM gateway_results WHERE provider_key = ?", String.class, targetCaptureKey);
        if (statuses.isEmpty()) {
            // Capture never reached the gateway: nothing to void.
            jdbc.update("""
                    INSERT INTO gateway_results (provider_key, kind, amount_minor, scenario, status, available_at)
                    VALUES (?, 'VOID', 0, 'SUCCESS', 'SUCCEEDED', now())
                    ON CONFLICT (provider_key) DO NOTHING
                    """, voidKey);
            return new GatewayResult(Outcome.SUCCESS, "capture_not_found_voided");
        }
        String captureStatus = statuses.getFirst();
        if ("PENDING".equals(captureStatus)) {
            jdbc.update("UPDATE gateway_results SET status = 'FAILED' WHERE provider_key = ?", targetCaptureKey);
            jdbc.update("""
                    INSERT INTO gateway_results (provider_key, kind, amount_minor, scenario, status, available_at)
                    VALUES (?, 'VOID', 0, 'SUCCESS', 'SUCCEEDED', now())
                    ON CONFLICT (provider_key) DO NOTHING
                    """, voidKey);
            return new GatewayResult(Outcome.SUCCESS, "pending_capture_voided");
        }
        if ("FAILED".equals(captureStatus)) {
            jdbc.update("""
                    INSERT INTO gateway_results (provider_key, kind, amount_minor, scenario, status, available_at)
                    VALUES (?, 'VOID', 0, 'SUCCESS', 'SUCCEEDED', now())
                    ON CONFLICT (provider_key) DO NOTHING
                    """, voidKey);
            return new GatewayResult(Outcome.SUCCESS, "failed_capture_voided");
        }
        return new GatewayResult(Outcome.UNKNOWN, "ALREADY_CAPTURED");
    }

    public GatewayResult queryStatus(String providerKey) {
        List<String> statuses = jdbc.queryForList("""
                SELECT status FROM gateway_results WHERE provider_key = ? AND available_at <= now()
                """, String.class, providerKey);
        if (statuses.isEmpty()) {
            return new GatewayResult(Outcome.UNKNOWN, "no_resolved_result_yet");
        }
        return switch (statuses.getFirst()) {
            case "SUCCEEDED" -> new GatewayResult(Outcome.SUCCESS, "query");
            case "FAILED" -> new GatewayResult(Outcome.FAILURE, "query");
            default -> new GatewayResult(Outcome.UNKNOWN, "query");
        };
    }

    public long capturedAmount(String providerKey) {
        Long amount = jdbc.queryForObject(
                "SELECT amount_minor FROM gateway_results WHERE provider_key = ?", Long.class, providerKey);
        return amount == null ? 0L : amount;
    }

    private String scenarioFor(String providerKey) {
        List<AppProperties.ScenarioRule> rules = properties.gateway().parsedRules();
        if (rules == null) {
            return "SUCCESS";
        }
        for (AppProperties.ScenarioRule rule : rules) {
            if (rule.providerKeyPrefix() != null && providerKey.startsWith(rule.providerKeyPrefix())) {
                return rule.scenario();
            }
        }
        return "SUCCESS";
    }

    private GatewayResult resolve(String providerKey, String kind, long amountMinor, String scenario) {
        // Gateway-side idempotency: a retried command with the same stable
        // providerKey receives the stored result instead of a second charge.
        List<String> existing = jdbc.queryForList(
                "SELECT status FROM gateway_results WHERE provider_key = ?", String.class, providerKey);
        if (!existing.isEmpty()) {
            return switch (existing.getFirst()) {
                case "SUCCEEDED" -> new GatewayResult(Outcome.SUCCESS, "replayed");
                case "FAILED" -> new GatewayResult(Outcome.FAILURE, "replayed");
                default -> new GatewayResult(Outcome.UNKNOWN, "replayed_pending");
            };
        }
        int delaySeconds = delayFor(providerKey);
        Instant availableAt = switch (scenario) {
            case "SUCCESS" -> Instant.now();
            case "FAILURE" -> Instant.now();
            case "LATE_SUCCESS", "UNKNOWN_THEN_SUCCESS" -> Instant.now().plusSeconds(delaySeconds);
            case "UNKNOWN_THEN_FAILURE" -> Instant.now().plusSeconds(delaySeconds);
            case "ALWAYS_UNKNOWN" -> Instant.now().plusSeconds(365 * 24 * 3600L);
            default -> Instant.now();
        };
        String status = switch (scenario) {
            case "SUCCESS" -> "SUCCEEDED";
            case "FAILURE" -> "FAILED";
            case "LATE_SUCCESS", "UNKNOWN_THEN_SUCCESS" -> "PENDING"; // resolves to SUCCEEDED
            case "UNKNOWN_THEN_FAILURE" -> "PENDING"; // resolves to FAILED
            case "ALWAYS_UNKNOWN" -> "PENDING";
            default -> "SUCCEEDED";
        };
        jdbc.update("""
                INSERT INTO gateway_results (provider_key, kind, amount_minor, scenario, status, available_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (provider_key) DO NOTHING
                """, providerKey, kind, amountMinor, scenario, status, java.sql.Timestamp.from(availableAt));
        return switch (scenario) {
            case "SUCCESS" -> new GatewayResult(Outcome.SUCCESS, scenario);
            case "FAILURE" -> new GatewayResult(Outcome.FAILURE, scenario);
            case "LATE_SUCCESS", "UNKNOWN_THEN_SUCCESS" -> new GatewayResult(Outcome.UNKNOWN, scenario);
            case "UNKNOWN_THEN_FAILURE" -> new GatewayResult(Outcome.UNKNOWN, scenario);
            case "ALWAYS_UNKNOWN" -> new GatewayResult(Outcome.UNKNOWN, scenario);
            default -> new GatewayResult(Outcome.UNKNOWN, scenario);
        };
    }

    private int delayFor(String providerKey) {
        List<AppProperties.ScenarioRule> rules = properties.gateway().parsedRules();
        if (rules == null) {
            return 3;
        }
        for (AppProperties.ScenarioRule rule : rules) {
            if (rule.providerKeyPrefix() != null && providerKey.startsWith(rule.providerKeyPrefix())) {
                return Math.max(1, rule.delaySeconds());
            }
        }
        return 3;
    }

    /** Startup assertion: the prod profile must not ship user-controllable outcomes. */
    public void assertProductionIsolation(String profile) {
        if (!"prod".equals(profile)) {
            return;
        }
        List<AppProperties.ScenarioRule> rules = properties.gateway().parsedRules();
        if (rules != null && !rules.isEmpty()) {
            throw new ApiException(ErrorCode.INTERNAL, "prod profile must not define gateway scenario rules");
        }
    }
}
