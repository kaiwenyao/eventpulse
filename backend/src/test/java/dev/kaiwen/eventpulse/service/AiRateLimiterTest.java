package dev.kaiwen.eventpulse.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import dev.kaiwen.eventpulse.common.AppProperties;

class AiRateLimiterTest {

    @Test
    void localWindowCountsAndResetsEachMinute() {
        AiRateLimiter limiter = new AiRateLimiter(new AppProperties());
        String bucket = "ip:1.2.3.4:" + Instant.now().getEpochSecond() / 60;
        // 固定窗口按分钟分桶：同一个桶里前 N 次放行，超过拒绝。
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(bucket + ":shared", 5)).isTrue();
        }
        assertThat(limiter.tryAcquire(bucket + ":shared", 5)).isFalse();
    }

    @Test
    void redisPathUsesSharedCounterAndFallsBackWhenRedisFails() {
        AiRateLimiter limiter = new AiRateLimiter(new AppProperties());
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(values);
        AtomicLong counter = new AtomicLong();
        org.mockito.Mockito.when(values.increment(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> counter.incrementAndGet());
        org.mockito.Mockito.when(redis.expire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class))).thenReturn(true);
        limiter.setRedis(redis);

        assertThat(limiter.tryAcquire("user:1", 2)).isTrue();
        assertThat(limiter.tryAcquire("user:1", 2)).isTrue();
        assertThat(limiter.tryAcquire("user:1", 2)).isFalse();

        // Redis 抖动：退回本地窗口，接口仍可用（第一次访问本地窗口）。
        org.mockito.Mockito.when(values.increment(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("redis down"));
        assertThat(limiter.tryAcquire("user:2", 1)).isTrue();
        assertThat(limiter.tryAcquire("user:2", 1)).isFalse();
    }

    @Test
    void nonPositiveLimitAlwaysAllows() {
        AiRateLimiter limiter = new AiRateLimiter(new AppProperties());
        assertThat(limiter.tryAcquire("bucket", 0)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleLocalWindowsArePrunedOnMinuteRollover() {
        AiRateLimiter limiter = new AiRateLimiter(new AppProperties());
        assertThat(limiter.tryAcquire("user:1", 5)).isTrue();
        assertThat(limiter.tryAcquire("user:2", 5)).isTrue();
        java.util.Map<String, Object> windows =
                (java.util.Map<String, Object>) ReflectionTestUtils.getField(limiter, "localWindows");
        assertThat(windows).hasSize(2);

        // 分钟切换后清一次：旧窗口条目被移除，Map 不随时间线性增长。
        long nextMinute = Instant.now().getEpochSecond() / 60 + 1;
        ReflectionTestUtils.invokeMethod(limiter, "pruneStaleWindows", nextMinute);
        assertThat(windows).isEmpty();

        // 同一分钟内重复调用不重复扫描。
        windows.put("manually-added", new Object());
        ReflectionTestUtils.invokeMethod(limiter, "pruneStaleWindows", nextMinute);
        assertThat(windows).hasSize(1);
    }
}
