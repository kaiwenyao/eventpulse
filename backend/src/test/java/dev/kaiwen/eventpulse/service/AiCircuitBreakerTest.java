package dev.kaiwen.eventpulse.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class AiCircuitBreakerTest {

    /** 可推进的假时钟：半开探针要等冷却期过去，真睡 30 秒的话这段逻辑没法测。 */
    private static final class FakeClock {
        private final AtomicLong now = new AtomicLong(1_000_000L);

        long get() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    @Test
    void opensAfterConsecutiveFailuresAndFailsFastWhileOpen() {
        FakeClock clock = new FakeClock();
        AiCircuitBreaker breaker = new AiCircuitBreaker(3, 30_000, clock::get);

        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.isOpen()).isFalse();

        breaker.recordFailure();
        assertThat(breaker.isOpen()).isTrue();
        // 打开期间直接拒绝，不再让请求挂满读超时才释放线程。
        assertThat(breaker.allowRequest()).isFalse();
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    void aSuccessInBetweenResetsTheConsecutiveCounter() {
        FakeClock clock = new FakeClock();
        AiCircuitBreaker breaker = new AiCircuitBreaker(3, 30_000, clock::get);

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        // 「连续」是真的连续：中间成功过就重新计数。
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void afterCooldownExactlyOneProbeIsLetThrough() {
        FakeClock clock = new FakeClock();
        AiCircuitBreaker breaker = new AiCircuitBreaker(2, 30_000, clock::get);
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.allowRequest()).isFalse();

        clock.advance(30_001);

        // 半开：只放一个探针，避免刚恢复就被一拥而上打垮。
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    void aFailingProbeKeepsTheBreakerOpenForAnotherWindow() {
        FakeClock clock = new FakeClock();
        AiCircuitBreaker breaker = new AiCircuitBreaker(1, 10_000, clock::get);
        breaker.recordFailure();
        clock.advance(10_001);

        assertThat(breaker.allowRequest()).isTrue();
        breaker.recordFailure();

        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    void aSuccessfulProbeClosesTheBreaker() {
        FakeClock clock = new FakeClock();
        AiCircuitBreaker breaker = new AiCircuitBreaker(1, 10_000, clock::get);
        breaker.recordFailure();
        clock.advance(10_001);
        assertThat(breaker.allowRequest()).isTrue();

        breaker.recordSuccess();

        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void zeroThresholdOrWindowDisablesTheBreakerEntirely() {
        AiCircuitBreaker disabled = AiCircuitBreaker.disabled();
        assertThat(disabled.isEnabled()).isFalse();
        for (int i = 0; i < 50; i++) {
            assertThat(disabled.allowRequest()).isTrue();
            disabled.recordFailure();
        }
        assertThat(disabled.isOpen()).isFalse();
    }
}
