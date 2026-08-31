package dev.kaiwen.eventpulse;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.web.RateLimiter;
import dev.kaiwen.eventpulse.exception.ApiException;
import dev.kaiwen.eventpulse.exception.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Rate limiter: Redis window counting, rejection and the in-process fallback. */
class RateLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setup() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    private RateLimiter limiter(String loginSpec) {
        AppProperties properties = new AppProperties(
                new AppProperties.Security("s", "p", Duration.ofMinutes(1), Duration.ofDays(1),
                        Duration.ofMinutes(10), List.of()),
                null, null, null, null,
                new AppProperties.RateLimit(loginSpec, "1/60", "1/60", "1/60", "1/60"), null);
        return new RateLimiter(redis, properties);
    }

    @Test
    void countsUnderLimitPassAndAboveFail() {
        when(valueOps.increment(org.mockito.ArgumentMatchers.contains(":user-1:")))
                .thenReturn(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        when(valueOps.increment(org.mockito.ArgumentMatchers.contains(":user-2:"))).thenReturn(1L);
        RateLimiter limiter = limiter("7/60");
        for (int i = 0; i < 7; i++) {
            limiter.check("login", "user-1");
        }
        assertThatThrownBy(() -> limiter.check("login", "user-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        // other buckets unaffected
        assertThatCode(() -> limiter.check("login", "user-2")).doesNotThrowAnyException();
    }

    @Test
    void redisOutageFallsBackToInProcessWindow() {
        when(valueOps.increment(anyString())).thenThrow(new org.springframework.data.redis
                .RedisConnectionFailureException("down"));
        RateLimiter limiter = limiter("2/60");
        limiter.check("login", "k");
        limiter.check("login", "k");
        assertThatThrownBy(() -> limiter.check("login", "k"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        // distinct keys get their own window
        assertThatCode(() -> limiter.check("login", "other")).doesNotThrowAnyException();
    }
}
