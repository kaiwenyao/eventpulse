package dev.kaiwen.eventpulse;

import java.time.Duration;
import java.util.List;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.config.ProdSecurityAssertions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Property records: rate limit specs, prod guards. */
class AppPropertiesTest {

    private AppProperties.Security security(String secret, String pepper) {
        return security(secret, pepper, Boolean.FALSE);
    }

    private AppProperties.Security security(String secret, String pepper, Boolean secureCookie) {
        return new AppProperties.Security(secret, pepper, Duration.ofMinutes(1), Duration.ofDays(1),
                Duration.ofMinutes(10), List.of(), secureCookie);
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
        assertThat(rateLimit.limit("anything-else")).isEqualTo(60);
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
    void prodAssertionsRejectDefaultSecrets() {
        assertThatThrownBy(() -> new ProdSecurityAssertions(
                new AppProperties(security("dev-only-x", "dev-only-y"), null, null, null, null, null, null))
                .assertHardening())
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> new ProdSecurityAssertions(
                new AppProperties(security("real", "real", Boolean.TRUE), null, null, null, null, null, null))
                .assertHardening())
                .doesNotThrowAnyException();
    }

    @Test
    void prodAssertionsRequireSecureRefreshCookie() {
        assertThatThrownBy(() -> new ProdSecurityAssertions(
                new AppProperties(security("real", "real", Boolean.FALSE), null, null, null, null, null, null))
                .assertHardening())
                .isInstanceOfSatisfying(IllegalStateException.class, e ->
                        assertThat(e).hasMessageContaining("refresh-cookie-secure"));
        assertThatThrownBy(() -> new ProdSecurityAssertions(
                new AppProperties(security("real", "real", null), null, null, null, null, null, null))
                .assertHardening())
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> new ProdSecurityAssertions(
                new AppProperties(security("real", "real", Boolean.TRUE), null, null, null, null, null, null))
                .assertHardening())
                .doesNotThrowAnyException();
    }
}
