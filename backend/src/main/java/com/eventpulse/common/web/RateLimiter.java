package com.eventpulse.common.web;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.eventpulse.common.AppProperties;
import com.eventpulse.common.error.ApiException;
import com.eventpulse.common.error.ErrorCode;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Sliding-window rate limiter. Redis is authoritative when reachable; an
 * in-process window keeps the limiter functional (per node) if Redis is down.
 */
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final AppProperties properties;
    private final Map<String, Window> localWindows = new ConcurrentHashMap<>();

    public RateLimiter(StringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void check(String bucket, String key) {
        long limit = properties.rateLimit().limit(bucket);
        long windowSeconds = properties.rateLimit().windowSeconds(bucket);
        String redisKey = "rl:%s:%s:%d".formatted(bucket, key, System.currentTimeMillis() / 1000 / windowSeconds);
        boolean allowed;
        try {
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, Duration.ofSeconds(windowSeconds + 1));
            }
            allowed = count == null || count <= limit;
        }
        catch (Exception e) {
            allowed = localAllow(redisKey, limit);
        }
        if (!allowed) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "rate limit exceeded, slow down");
        }
    }

    private synchronized boolean localAllow(String key, long limit) {
        long window = System.currentTimeMillis() / 1000 / 60;
        Window w = localWindows.computeIfAbsent(key, k -> new Window());
        if (w.window != window) {
            w.window = window;
            w.count = 0;
        }
        w.count++;
        return w.count <= limit;
    }

    private static final class Window {
        long window;
        long count;
    }
}
