package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import dev.kaiwen.eventpulse.common.AppProperties;

/**
 * AI 接口的用户 / IP 级限流（固定窗口，次/分钟），防止成本失控。
 *
 * Redis 可用时用 INCR + EXPIRE（多实例共享同一个配额）；Redis 未启用或
 * 暂时不可用时退化为实例本地的内存窗口 —— 退化只影响多实例叠加的精度，
 * 单实例行为不变，接口本身不受影响。
 */
@Component
public class AiRateLimiter {

    private final AppProperties properties;
    private StringRedisTemplate redis;

    /** key → (windowStartEpochMinute, count)。仅无 Redis 时使用。 */
    private final Map<String, Window> localWindows = new ConcurrentHashMap<>();

    /** 上次本地窗口清理所在的分钟号：只在分钟切换时扫一遍，摊薄清理成本。 */
    private volatile long lastPruneMinute = -1;

    public AiRateLimiter(AppProperties properties) {
        this.properties = properties;
    }

    @Autowired(required = false)
    public void setRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 超出配额返回 false。 */
    public boolean tryAcquire(String bucket, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return true;
        }
        String key = "ai:rate:" + bucket + ":" + Instant.now().getEpochSecond() / 60;
        if (redis != null) {
            try {
                Long count = redis.opsForValue().increment(key);
                redis.expire(key, java.time.Duration.ofSeconds(120));
                return count == null || count <= limitPerMinute;
            }
            catch (Exception e) {
                // Redis 抖动时落回本地窗口，AI 接口仍可用。
            }
        }
        return tryAcquireLocal(key, limitPerMinute);
    }

    private boolean tryAcquireLocal(String key, int limitPerMinute) {
        long minute = Instant.now().getEpochSecond() / 60;
        pruneStaleWindows(minute);
        Window window = localWindows.compute(key, (k, old) ->
                old == null || old.minute != minute ? new Window(minute) : old);
        return window.count.incrementAndGet() <= limitPerMinute;
    }

    /**
     * 本地窗口按分钟分桶，旧分钟条目不会自然过期：不清理的话 Map 会随
     * 时间线性增长（每个活跃 bucket 每分钟新增一条）。分钟切换时清一次。
     */
    void pruneStaleWindows(long minute) {
        if (lastPruneMinute == minute) {
            return;
        }
        lastPruneMinute = minute;
        localWindows.values().removeIf(w -> w.minute < minute);
    }

    public int userLimit() {
        return properties.getAi().getRateLimitUserPerMinute();
    }

    public int ipLimit() {
        return properties.getAi().getRateLimitIpPerMinute();
    }

    private static final class Window {
        private final long minute;
        private final AtomicLong count = new AtomicLong();

        private Window(long minute) {
            this.minute = minute;
        }
    }
}
