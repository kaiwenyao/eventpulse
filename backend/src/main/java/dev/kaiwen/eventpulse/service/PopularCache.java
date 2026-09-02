package dev.kaiwen.eventpulse.service;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 热门活动的 Redis 缓存：所有 API 实例读写同一份，实例之间结果一致。
 * 没有本地 JVM 副本；Redis 暂时不可用时调用方直接回源 PostgreSQL。
 */
@Component
public class PopularCache {

    static final String POPULAR_IDS_KEY = "popular:ids";
    private static final Duration TTL = Duration.ofSeconds(30);

    private StringRedisTemplate redis;
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    public void setRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 读缓存的活动 id 列表；未命中或 Redis 不可用时返回 null（由调用方回源数据库）。 */
    public List<Long> readIds() {
        if (redis == null) {
            return null;
        }
        try {
            String ids = redis.opsForValue().get(POPULAR_IDS_KEY);
            if (ids == null || ids.isBlank()) {
                return null;
            }
            List<Long> parsed = java.util.Arrays.stream(ids.split(","))
                    .filter(s -> !s.isBlank())
                    .map(Long::valueOf)
                    .toList();
            return parsed.isEmpty() ? null : parsed;
        }
        catch (Exception e) {
            countFallback();
            return null;
        }
    }

    /** 写回缓存；Redis 不可用时静默跳过（接口已经拿到数据库结果）。 */
    public void writeIds(List<Long> ids) {
        if (redis == null || ids.isEmpty()) {
            return;
        }
        try {
            redis.opsForValue().set(POPULAR_IDS_KEY, String.join(",", ids.stream().map(String::valueOf).toList()),
                    TTL);
        }
        catch (Exception e) {
            countFallback();
        }
    }

    /** 活动发布、取消、归档或售票数量变化后删除热门缓存，下次请求重新计算。 */
    public void evict() {
        if (redis == null) {
            return;
        }
        try {
            redis.delete(POPULAR_IDS_KEY);
        }
        catch (Exception e) {
            countFallback();
        }
    }

    /** 缓存降级次数进 Micrometer：多实例在监控系统里分别查看、汇总。 */
    private void countFallback() {
        if (meterRegistry != null) {
            meterRegistry.counter("eventpulse.cache.fallbacks").increment();
        }
    }
}
