package dev.kaiwen.eventpulse.worker;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 活动生命周期任务（仅 worker Profile）：
 * 用两条数据库条件更新推进状态——数据库只更新仍满足条件的记录，
 * 第二个 Worker 再执行时更新 0 行，既不会覆盖新状态，也不会出现乐观锁异常。
 *
 * 状态只允许按 PUBLISHED → ONGOING → FINISHED 的方向前进。
 */
@Component
@Profile("worker")
public class EventLifecycleWorker {

    private static final Logger log = LoggerFactory.getLogger(EventLifecycleWorker.class);

    private final EventRepository events;
    private final Timer scanTimer;
    private final MeterRegistry meters;

    public EventLifecycleWorker(EventRepository events, MeterRegistry meters) {
        this.events = events;
        this.meters = meters;
        this.scanTimer = Timer.builder("eventpulse.lifecycle.scan")
                .description("Activity lifecycle conditional-update scan")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${eventpulse.lifecycle.fixed-delay-ms:60000}")
    @Transactional
    public void advance() {
        scanTimer.record(() -> {
            Instant now = Instant.now();
            try {
                int started = events.startPublishedEvents(EventStatus.PUBLISHED, EventStatus.ONGOING, now);
                int finished = events.finishOngoingEvents(EventStatus.ONGOING, EventStatus.FINISHED, now);
                if (started > 0 || finished > 0) {
                    log.info("活动生命周期更新：{} 场活动开始（ONGOING），{} 场活动结束（FINISHED）", started, finished);
                }
                meters.counter("eventpulse.lifecycle.started").increment(started);
                meters.counter("eventpulse.lifecycle.finished").increment(finished);
            }
            catch (RuntimeException failure) {
                meters.counter("eventpulse.lifecycle.failures").increment();
                throw failure;
            }
        });
    }
}
