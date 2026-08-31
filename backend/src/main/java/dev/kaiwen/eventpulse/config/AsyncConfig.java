package dev.kaiwen.eventpulse.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async wiring. SSE status pushes must never run on the transaction thread:
 * state-change events are delivered only AFTER_COMMIT (no phantom states on
 * rollback) and on a dedicated bounded executor, so a slow browser socket can
 * never hold booking/quota/inventory row locks or stall the caller
 * (plan §3.1/§17.3: no slow-client I/O inside transactions).
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "ssePushExecutor")
    public ThreadPoolTaskExecutor ssePushExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("sse-push-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        // SSE is a "hint" channel (plan §5.4): the REST polling path re-syncs
        // facts, so under overload DISCARD is the correct backpressure instead
        // of stalling the caller that just committed the transaction. Expired
        // or broken emitters are pruned on the next heartbeat regardless.
        executor.setRejectedExecutionHandler((runnable, executor1) ->
                org.slf4j.LoggerFactory.getLogger("sse-push")
                        .warn("sse push queue saturated; dropping push (client re-syncs via REST)"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}