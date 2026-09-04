package dev.kaiwen.eventpulse.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import dev.kaiwen.eventpulse.common.AppProperties;

/**
 * AI 的每日 token 预算（用户级 + 全局级）。
 *
 * 与 {@link AiRateLimiter} 的「次/分钟」互补：限流挡住短时间的脚本刷调用，
 * 预算挡住长尾的成本失控（请求不多但每次都烧掉极多 token）。
 *
 * 语义刻意选成「先检查、后记账」：检查用的是今天到此刻的累计值，所以跨过阈值
 * 的那一次请求仍会被放行并小幅超支。预算是成本护栏而不是计费闸门，为了精确而
 * 在调用前预扣、失败再退还并不划算。
 *
 * Redis 可用时用 INCRBY（多副本共享同一份配额）；不可用时退化为实例本地计数
 * —— 退化只影响多副本汇总精度，单副本行为不变。读写都失败时按放行处理：AI 是
 * 增强功能，不该因为预算账本读不到就整体不可用。
 */
@Component
public class AiTokenBudget {

    /** 日桶保留 48h：跨天边界时旧桶还读得到，又不会无限堆积。 */
    static final Duration BUCKET_TTL = Duration.ofHours(48);

    private static final long SECONDS_PER_DAY = 86_400L;
    private static final String GLOBAL_BUCKET = "global";

    private final AppProperties properties;
    private StringRedisTemplate redis;

    /** key → 当日累计 token。仅在无 Redis / Redis 抖动时使用。 */
    private final Map<String, AtomicLong> localBuckets = new ConcurrentHashMap<>();

    /** 上次清理所在的天号：只在跨天时扫一遍，摊薄清理成本。 */
    private volatile long lastPruneDay = -1;

    public AiTokenBudget(AppProperties properties) {
        this.properties = properties;
    }

    @Autowired(required = false)
    public void setRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 今日用量是否仍在预算内。userId 为 null（游客）时只看全局桶。 */
    public boolean hasBudget(Long userId) {
        long day = epochDay();
        int globalLimit = properties.getAi().getDailyTokenBudgetGlobal();
        if (globalLimit > 0 && read(GLOBAL_BUCKET, day) >= globalLimit) {
            return false;
        }
        int userLimit = properties.getAi().getDailyTokenBudgetUser();
        if (userId == null || userLimit <= 0) {
            return true;
        }
        return read(userBucket(userId), day) < userLimit;
    }

    /**
     * 记录一次真实 LLM 调用的用量。缓存命中不会调用这里 —— 没有真的调用模型，
     * 就不该扣预算。
     */
    public void record(Long userId, Integer inputTokens, Integer outputTokens) {
        long total = nonNegative(inputTokens) + nonNegative(outputTokens);
        if (total <= 0) {
            return;
        }
        long day = epochDay();
        increment(GLOBAL_BUCKET, day, total);
        if (userId != null) {
            increment(userBucket(userId), day, total);
        }
    }

    private long read(String bucket, long day) {
        String key = key(bucket, day);
        if (redis != null) {
            try {
                String value = redis.opsForValue().get(key);
                return value == null ? 0L : Long.parseLong(value);
            }
            catch (Exception e) {
                // Redis 抖动或值被写坏：落回本地计数，AI 接口仍可用。
            }
        }
        AtomicLong local = localBuckets.get(key);
        return local == null ? 0L : local.get();
    }

    private void increment(String bucket, long day, long amount) {
        String key = key(bucket, day);
        if (redis != null) {
            try {
                redis.opsForValue().increment(key, amount);
                redis.expire(key, BUCKET_TTL);
                return;
            }
            catch (Exception e) {
                // 落回本地计数。
            }
        }
        pruneStaleBuckets(day);
        localBuckets.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(amount);
    }

    /**
     * 本地桶按天分键，旧天的条目不会自然过期：不清理的话 Map 会随时间线性增长。
     * 跨天时清一次。
     */
    void pruneStaleBuckets(long day) {
        if (lastPruneDay == day) {
            return;
        }
        lastPruneDay = day;
        String suffix = ":" + day;
        localBuckets.keySet().removeIf(key -> !key.endsWith(suffix));
    }

    private static String key(String bucket, long day) {
        return "ai:tokens:" + bucket + ":" + day;
    }

    private static String userBucket(Long userId) {
        return "user:" + userId;
    }

    private static long epochDay() {
        return Instant.now().getEpochSecond() / SECONDS_PER_DAY;
    }

    private static long nonNegative(Integer value) {
        return value == null || value < 0 ? 0L : value;
    }
}
