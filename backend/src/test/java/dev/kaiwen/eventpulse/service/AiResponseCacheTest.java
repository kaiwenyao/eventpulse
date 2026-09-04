package dev.kaiwen.eventpulse.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiResponseCacheTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private AiResponseCache cache() {
        return new AiResponseCache(new ObjectMapper(), registry);
    }

    private double fallbacks() {
        return registry.counter("eventpulse.cache.fallbacks").count();
    }

    record Sample(String title, int score) {
    }

    @Test
    void withoutRedisTheCacheIsSimplyOff() {
        AiResponseCache cache = cache();
        assertThat(cache.isAvailable()).isFalse();
        // put 静默忽略、get 恒为未命中：调用方据此直接回源，真的去调一次 LLM。
        cache.put("k", new Sample("t", 1), Duration.ofMinutes(1));
        assertThat(cache.get("k", Sample.class)).isEmpty();
        assertThat(fallbacks()).isZero();
    }

    @Test
    void roundTripsThroughRedis() {
        Map<String, String> store = new HashMap<>();
        AiResponseCache cache = cache();
        cache.setRedis(fakeRedis(store));

        assertThat(cache.isAvailable()).isTrue();
        assertThat(cache.get("k", Sample.class)).isEmpty();

        cache.put("k", new Sample("周末爵士夜", 7), Duration.ofMinutes(1));
        assertThat(cache.get("k", Sample.class)).contains(new Sample("周末爵士夜", 7));
        assertThat(fallbacks()).isZero();
    }

    @Test
    void unreadableCachedValueCountsAsMissNotAsAnError() {
        Map<String, String> store = new HashMap<>();
        // 结构变更后残留的旧值：反序列化不了就当未命中，绝不让缓存问题变成接口错误。
        store.put("k", "{\"nope\":");
        AiResponseCache cache = cache();
        cache.setRedis(fakeRedis(store));

        assertThat(cache.get("k", Sample.class)).isEmpty();
        assertThat(fallbacks()).isEqualTo(1.0);
    }

    @Test
    void redisOutageOnReadAndWriteIsCountedAndSwallowed() {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        Mockito.when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        AiResponseCache cache = cache();
        cache.setRedis(redis);

        assertThat(cache.get("k", Sample.class)).isEmpty();
        cache.put("k", new Sample("t", 1), Duration.ofMinutes(1));
        assertThat(fallbacks()).isEqualTo(2.0);
    }

    @Test
    void nullValueOrNonPositiveTtlIsNotWritten() {
        Map<String, String> store = new HashMap<>();
        AiResponseCache cache = cache();
        cache.setRedis(fakeRedis(store));

        cache.put("k", null, Duration.ofMinutes(1));
        cache.put("k", new Sample("t", 1), Duration.ZERO);
        cache.put("k", new Sample("t", 1), Duration.ofMinutes(-1));
        cache.put("k", new Sample("t", 1), null);
        assertThat(store).isEmpty();
    }

    @Test
    void keysAreStableDistinctAndVersioned() {
        AiResponseCache cache = cache();

        assertThat(cache.key("improve", "a", "b")).isEqualTo(cache.key("improve", "a", "b"));
        assertThat(cache.key("improve", "a", "b")).isNotEqualTo(cache.key("discovery", "a", "b"));
        // 分隔符的意义：拼接歧义不能撞 key。
        assertThat(cache.key("improve", "ab", "c")).isNotEqualTo(cache.key("improve", "a", "bc"));
        // null 与空串是同一个输入（Spring 传上来的未填字段就是 null）。
        assertThat(cache.key("improve", (Object) null)).isEqualTo(cache.key("improve", ""));
        // 版本前缀在 key 里，改提示词时 +1 就能让所有旧值失效。
        assertThat(cache.key("improve", "a")).contains(":" + AiResponseCache.SCHEMA_VERSION + ":");
    }

    private static StringRedisTemplate fakeRedis(Map<String, String> store) {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(values);
        Mockito.when(values.get(ArgumentMatchers.anyString()))
                .thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(values).set(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(Duration.class));
        return redis;
    }
}
