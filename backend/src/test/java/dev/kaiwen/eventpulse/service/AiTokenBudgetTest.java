package dev.kaiwen.eventpulse.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import dev.kaiwen.eventpulse.common.AppProperties;

class AiTokenBudgetTest {

    private static AppProperties propertiesWith(int userLimit, int globalLimit) {
        AppProperties properties = new AppProperties();
        properties.getAi().setDailyTokenBudgetUser(userLimit);
        properties.getAi().setDailyTokenBudgetGlobal(globalLimit);
        return properties;
    }

    @Test
    void localCounterBlocksOnceTheDailyUserBudgetIsUsedUp() {
        AiTokenBudget budget = new AiTokenBudget(propertiesWith(100, 0));

        assertThat(budget.hasBudget(1L)).isTrue();
        budget.record(1L, 60, 30);
        // 90 < 100：还没到，最后一次仍然放行（先检查、后记账，允许小幅超支）。
        assertThat(budget.hasBudget(1L)).isTrue();
        budget.record(1L, 20, 0);
        assertThat(budget.hasBudget(1L)).isFalse();
        // 预算是按用户分开的，别人不受影响。
        assertThat(budget.hasBudget(2L)).isTrue();
    }

    @Test
    void globalBudgetAlsoCoversGuests() {
        AiTokenBudget budget = new AiTokenBudget(propertiesWith(0, 50));

        assertThat(budget.hasBudget(null)).isTrue();
        budget.record(null, 50, 0);
        // 游客没有用户桶，但仍然计进全局桶并被它挡住。
        assertThat(budget.hasBudget(null)).isFalse();
        assertThat(budget.hasBudget(7L)).isFalse();
    }

    @Test
    void zeroLimitDisablesTheBudget() {
        AiTokenBudget budget = new AiTokenBudget(propertiesWith(0, 0));
        budget.record(1L, 999999, 999999);
        assertThat(budget.hasBudget(1L)).isTrue();
    }

    @Test
    void negativeAndNullUsageIsIgnored() {
        AiTokenBudget budget = new AiTokenBudget(propertiesWith(10, 0));
        budget.record(1L, null, null);
        budget.record(1L, -5, -5);
        assertThat(budget.hasBudget(1L)).isTrue();
    }

    @Test
    void redisPathSharesTheCounterAcrossReplicas() {
        AiTokenBudget budget = new AiTokenBudget(propertiesWith(100, 0));
        Map<String, AtomicLong> store = new HashMap<>();
        budget.setRedis(fakeRedis(store));

        budget.record(3L, 70, 40);
        assertThat(store.keySet()).anyMatch(key -> key.startsWith("ai:tokens:user:3:"));
        // 读到的是 Redis 里的共享值，不是本地内存。
        assertThat(budget.hasBudget(3L)).isFalse();
    }

    @Test
    void redisOutageFallsBackToLocalCountingInsteadOfFailing() {
        AiTokenBudget budget = new AiTokenBudget(propertiesWith(10, 0));
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(values);
        Mockito.when(values.increment(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong()))
                .thenThrow(new IllegalStateException("redis down"));
        Mockito.when(values.get(ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("redis down"));
        budget.setRedis(redis);

        // 读写都失败：AI 仍可用（放行），并且本地计数照常累加。
        assertThat(budget.hasBudget(4L)).isTrue();
        budget.record(4L, 20, 0);
        assertThat(budget.hasBudget(4L)).isFalse();
    }

    @Test
    void corruptRedisValueIsTreatedAsZeroRatherThanBlowingUp() {
        AiTokenBudget budget = new AiTokenBudget(propertiesWith(10, 0));
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(values);
        Mockito.when(values.get(ArgumentMatchers.anyString())).thenReturn("not-a-number");
        budget.setRedis(redis);

        assertThat(budget.hasBudget(5L)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleLocalBucketsArePrunedOnDayRollover() {
        AiTokenBudget budget = new AiTokenBudget(propertiesWith(1000, 0));
        budget.record(1L, 1, 0);
        budget.record(2L, 1, 0);
        Map<String, Object> buckets =
                (Map<String, Object>) ReflectionTestUtils.getField(budget, "localBuckets");
        // 每次 record 会写用户桶 + 全局桶。
        assertThat(buckets).hasSize(3);

        ReflectionTestUtils.invokeMethod(budget, "pruneStaleBuckets", 999999L);
        assertThat(buckets).isEmpty();
    }

    /** 用一个 Map 冒充 Redis 的 INCRBY / GET，验证走的是共享计数而不是本地内存。 */
    private static StringRedisTemplate fakeRedis(Map<String, AtomicLong> store) {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(values);
        Mockito.when(values.increment(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> store.computeIfAbsent(inv.getArgument(0), k -> new AtomicLong())
                        .addAndGet(inv.getArgument(1)));
        Mockito.when(values.get(ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    AtomicLong value = store.get(inv.<String>getArgument(0));
                    return value == null ? null : String.valueOf(value.get());
                });
        Mockito.when(redis.expire(ArgumentMatchers.anyString(), ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);
        return redis;
    }
}
