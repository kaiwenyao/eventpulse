package dev.kaiwen.eventpulse.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Spring Boot → Python AI 服务的熔断器。
 *
 * 手写而不是引入 resilience4j：需求只有「连续失败就先别打了」，与
 * {@link AiRateLimiter} / {@link PopularCache} 同属这个仓库的手写风格，pom 里
 * 也没有相应依赖。
 *
 * 两个刻意的设计：
 *
 * 1. 只统计传输层故障（连不上 / 读超时 / 503 / 504），不统计应用层 502。Python
 *    在 LlmOutputError、AgentExecutionError 时返回 502，那是「模型这次没吐好」，
 *    进程完全健康；把它计进来的话，连续 5 次模型抽风就会把好端端的服务熔断掉。
 * 2. 时钟可注入。半开探针要等 openMillis 过去，真按 30 秒睡的话这段逻辑根本没法
 *    写单测，只能留成未覆盖行。
 *
 * 熔断本身不是线程耗尽的解药（要连续失败若干次才打开，并发下「连续」也不成立），
 * 真正压住 Tomcat 线程占用的是按端点分开的读超时；这里只负责在上游确实躺了之后
 * 让后续请求立刻失败，而不是每个都挂满读超时。
 */
public class AiCircuitBreaker {

    private final int failureThreshold;
    private final long openMillis;
    private final LongSupplier clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 熔断打开到这个时刻为止；0 表示闭合。 */
    private volatile long openUntil;
    /** 半开时只放一个探针过去，避免刚恢复就被一拥而上打垮。 */
    private final AtomicBoolean probeInFlight = new AtomicBoolean();

    public AiCircuitBreaker(int failureThreshold, long openMillis) {
        this(failureThreshold, openMillis, System::currentTimeMillis);
    }

    AiCircuitBreaker(int failureThreshold, long openMillis, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
        this.clock = clock;
    }

    /** 永远放行的熔断器：给不该被熔断影响的路径（例如测试注入的客户端）用。 */
    static AiCircuitBreaker disabled() {
        return new AiCircuitBreaker(0, 0);
    }

    public boolean isEnabled() {
        return failureThreshold > 0 && openMillis > 0;
    }

    /**
     * @return true 表示可以发请求；false 表示熔断打开、应当立刻降级。
     */
    public boolean allowRequest() {
        if (!isEnabled() || openUntil == 0) {
            return true;
        }
        if (clock.getAsLong() < openUntil) {
            return false;
        }
        // 冷却结束：进入半开，只有抢到探针的那一个请求可以真的出去。
        return probeInFlight.compareAndSet(false, true);
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntil = 0;
        probeInFlight.set(false);
    }

    /** 只有传输层故障才该调用这里；应用层错误请走 {@link #recordSuccess()} 之外的静默路径。 */
    public void recordFailure() {
        probeInFlight.set(false);
        if (!isEnabled()) {
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntil = clock.getAsLong() + openMillis;
            consecutiveFailures.set(0);
        }
    }

    /** 供指标与测试观察当前状态。 */
    public boolean isOpen() {
        return isEnabled() && openUntil != 0 && clock.getAsLong() < openUntil;
    }
}
