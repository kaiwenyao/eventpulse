package dev.kaiwen.eventpulse;

import java.time.Duration;
import java.util.List;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.config.ProdSecurityAssertions;
import dev.kaiwen.eventpulse.exception.ApiException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Property records: gateway scenario spec parsing, rate limit specs, prod guards. */
class AppPropertiesTest {

    private AppProperties.Security security(String secret, String pepper) {
        return security(secret, pepper, Boolean.FALSE);
    }

    private AppProperties.Security security(String secret, String pepper, Boolean secureCookie) {
        return new AppProperties.Security(secret, pepper, Duration.ofMinutes(1), Duration.ofDays(1),
                Duration.ofMinutes(10), List.of(), secureCookie);
    }

    @Test
    void gatewaySpecParsingCoversAllShapes() {
        var empty = new AppProperties.Gateway("", Duration.ZERO).parsedRules();
        assertThat(empty).isEmpty();

        var nullSpec = new AppProperties.Gateway(null, Duration.ZERO).parsedRules();
        assertThat(nullSpec).isEmpty();

        var rules = new AppProperties.Gateway("pi-late:LATE_SUCCESS:5;;pi-fail:failure",
                Duration.ZERO).parsedRules();
        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).providerKeyPrefix()).isEqualTo("pi-late");
        assertThat(rules.get(0).scenario()).isEqualTo("LATE_SUCCESS");
        assertThat(rules.get(0).delaySeconds()).isEqualTo(5);
        assertThat(rules.get(1).scenario()).isEqualTo("FAILURE"); // upper-cased
        assertThat(rules.get(1).delaySeconds()).isEqualTo(3); // default
    }

    @Test
    void rateLimitSpecsPerBucket() {
        var rateLimit = new AppProperties.RateLimit("7/60", "3/60", "9/30", "11/60", "2/10");
        assertThat(rateLimit.limit("login")).isEqualTo(7);
        assertThat(rateLimit.windowSeconds("login")).isEqualTo(60);
        assertThat(rateLimit.limit("register")).isEqualTo(3);
        assertThat(rateLimit.limit("redeem")).isEqualTo(9);
        assertThat(rateLimit.windowSeconds("redeem")).isEqualTo(30);
        assertThat(rateLimit.limit("interactions")).isEqualTo(11);
        assertThat(rateLimit.limit("reauth")).isEqualTo(2);
        assertThat(rateLimit.windowSeconds("reauth")).isEqualTo(10);
        assertThat(rateLimit.limit("anything-else")).isEqualTo(60); // default spec
        assertThat(rateLimit.windowSeconds("anything-else")).isEqualTo(60);
    }

    @Test
    void secretsAreDefaultsDetection() {
        assertThat(security("dev-only-xxx", "dev-only-yyy").secretsAreDefaults()).isTrue();
        assertThat(security("real-secret-value", "dev-only-yyy").secretsAreDefaults()).isTrue();
        assertThat(security(null, "pepper").secretsAreDefaults()).isTrue();
        assertThat(security("real-secret-value", "real-pepper-value").secretsAreDefaults()).isFalse();
    }

    @Test
    void prodAssertionsRejectDefaultSecretsAndScenarioRules() {
        assertThatThrownBy(() -> new ProdSecurityAssertions(
                new AppProperties(security("dev-only-x", "dev-only-y"), null, null, null,
                        new AppProperties.Gateway("", Duration.ZERO), null, null)).assertHardening())
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new ProdSecurityAssertions(
                new AppProperties(security("real", "real"), null, null, null,
                        new AppProperties.Gateway("pi-fail:FAILURE", Duration.ZERO), null, null))
                .assertHardening())
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> new ProdSecurityAssertions(
                new AppProperties(security("real", "real", Boolean.TRUE), null, null, null,
                        new AppProperties.Gateway("", Duration.ZERO), null, null)).assertHardening())
                .doesNotThrowAnyException();
    }

    @Test
    void prodAssertionsRequireSecureRefreshCookie() {
        // Real secrets + no scenario rules but Secure cookie off -> refused.
        assertThatThrownBy(() -> new ProdSecurityAssertions(
                new AppProperties(security("real", "real", Boolean.FALSE), null, null, null,
                        new AppProperties.Gateway("", Duration.ZERO), null, null)).assertHardening())
                .isInstanceOfSatisfying(IllegalStateException.class, e ->
                        assertThat(e).hasMessageContaining("refresh-cookie-secure"));
        assertThatThrownBy(() -> new ProdSecurityAssertions(
                new AppProperties(security("real", "real", null), null, null, null,
                        new AppProperties.Gateway("", Duration.ZERO), null, null)).assertHardening())
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> new ProdSecurityAssertions(
                new AppProperties(security("real", "real", Boolean.TRUE), null, null, null,
                        new AppProperties.Gateway("", Duration.ZERO), null, null)).assertHardening())
                .doesNotThrowAnyException();
    }
}
