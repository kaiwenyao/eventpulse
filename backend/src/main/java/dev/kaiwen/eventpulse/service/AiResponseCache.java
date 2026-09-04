package dev.kaiwen.eventpulse.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * AI 结果缓存：同样的输入不再重复付费调用 LLM。
 *
 * 与 {@link PopularCache} 同一套约定：只用 StringRedisTemplate、没有本地 JVM 副本、
 * Redis 不可用时直接当作未命中回源（真的去调一次 LLM），失败计入既有的
 * eventpulse.cache.fallbacks 指标。
 *
 * 缓存什么、不缓存什么由调用方决定 —— 这里只负责存取，因为两条链的可缓存条件
 * 完全不同（文案助手按请求内容缓存，发现助手只缓存没有用户上下文的游客首轮提问）。
 */
@Component
public class AiResponseCache {

    /**
     * key 的版本前缀。提示词、温度、输出结构一改，旧答案就不该再从热 Redis 里发出去；
     * 改这些的同一个提交里把这个值 +1 即可让所有旧 key 自然失效。
     */
    static final String SCHEMA_VERSION = "v1";

    /** 分隔符用不可见的 Unit Separator：避免 "ab"+"c" 与 "a"+"bc" 撞同一个 key。 */
    private static final char FIELD_SEPARATOR = (char) 0x1f;

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private StringRedisTemplate redis;

    public AiResponseCache(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Autowired(required = false)
    public void setRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 没有 Redis 时缓存整体关闭：调用方据此跳过 key 计算与读写。 */
    public boolean isAvailable() {
        return redis != null;
    }

    /**
     * 把若干字段拼成稳定的缓存 key。
     *
     * 调用方必须排除 requestId 这类每次都变的字段，否则命中率恒为 0 —— 这正是
     * 不能直接哈希发给 Python 的 payload 的原因（那里第一个字段就是随机 UUID）。
     */
    public String key(String prefix, Object... parts) {
        StringBuilder raw = new StringBuilder();
        for (Object part : parts) {
            raw.append(part == null ? "" : part.toString()).append(FIELD_SEPARATOR);
        }
        return "ai:cache:" + prefix + ":" + SCHEMA_VERSION + ":" + sha256(raw.toString());
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, type));
        }
        catch (Exception e) {
            // 缓存读失败（Redis 抖动 / 结构变更后的旧值反序列化不了）一律当未命中：
            // 大不了多花一次 LLM 调用，绝不让缓存问题变成接口错误。
            countFallback();
            return Optional.empty();
        }
    }

    public void put(String key, Object value, Duration ttl) {
        if (redis == null || value == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        }
        catch (Exception e) {
            countFallback();
        }
    }

    private void countFallback() {
        meterRegistry.counter("eventpulse.cache.fallbacks").increment();
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法，走到这里说明运行环境已经不可信。
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
