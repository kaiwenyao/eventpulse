package dev.kaiwen.eventpulse.outbox;

import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.kaiwen.eventpulse.entity.OutboxEvent;
import dev.kaiwen.eventpulse.outbox.OutboxStatusService.FailureAction;
import dev.kaiwen.eventpulse.repository.OutboxRepository;

/**
 * 把 Outbox 里待发送的消息发给 Kafka，并且：
 * 1. 先用一条原子 UPDATE 领取一批消息（多 Worker 不会同时处理同一条）；
 * 2. 等 Kafka 明确确认成功后才标记 published_at（不提前宣布成功）；
 * 3. Kafka 发送失败与数据库标记失败分开处理，数据库失败不会被误记成发送失败；
 * 4. 明确是消息本身问题的坏消息隔离（failed_at）后继续处理后面的消息；
 * 5. 明确的临时故障（如 Kafka 不可用）释放租约并结束本轮，下一轮从同一条继续，保持顺序；
 * 6. Worker 意外退出后领取租约（claimed_until）到期，其他 Worker 可以接手，不会永久卡住。
 */
@Component
@Profile("worker")
public class OutboxRelay {

    /** 等待 Kafka 发送结果的秒数；可配置，超时测试可以把它调小。 */
    public static final String FUTURE_WAIT_SECONDS_PROPERTY = "eventpulse.outbox.future-wait-seconds";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxStatusService outboxStatus;
    private final long futureWaitSeconds;
    private final int batchSize;
    private final long claimSeconds;
    /** 本 Worker 的标识：写进 claimed_by，排查时能看到是哪个实例领走的。 */
    private final String workerId;

    public OutboxRelay(
            OutboxRepository outbox,
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxStatusService outboxStatus,
            @Value("${eventpulse.outbox.future-wait-seconds:12}") long futureWaitSeconds,
            @Value("${eventpulse.outbox.batch-size:50}") int batchSize,
            @Value("${eventpulse.outbox.claim-seconds:60}") long claimSeconds) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.outboxStatus = outboxStatus;
        this.futureWaitSeconds = futureWaitSeconds;
        this.batchSize = batchSize;
        this.claimSeconds = claimSeconds;
        this.workerId = workerId();
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        // 每轮一个一次性 token：领取后按 token 取回本批消息，不会取回其他 Worker 的。
        String token = workerId + ":" + UUID.randomUUID();
        Instant now = Instant.now();
        int claimed;
        try {
            claimed = outboxStatus.claimBatch(token, now.plusSeconds(claimSeconds), now, batchSize);
        }
        catch (RuntimeException databaseFailure) {
            log.warn("Outbox 领取失败，本轮跳过", databaseFailure);
            return;
        }
        if (claimed == 0) {
            return;
        }
        publishClaimed(token);
    }

    private void publishClaimed(String token) {
        for (OutboxEvent event : outbox.findByClaimedByOrderByIdAsc(token)) {
            // message_key 决定 partition：同一订单的消息按顺序进同一分区。
            String key = event.getMessageKey() == null ? event.getDedupKey() : event.getMessageKey();
            try {
                kafkaTemplate.send(event.getTopic(), key, event.getPayload())
                        .get(futureWaitSeconds, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Outbox relay 被中断，提前结束本轮 id={}", event.getId());
                return;
            }
            catch (Exception sendFailure) {
                FailureAction action = outboxStatus.recordPublishFailure(event.getId(), sendFailure);
                outboxStatus.releaseClaim(token, event.getId());
                if (action == FailureAction.QUARANTINED) {
                    log.warn("Outbox 消息已隔离 id={} type={} error={}",
                            event.getId(), event.getEventType(), sendFailure.toString());
                    // 这条消息本身有问题。本轮继续处理后面的消息。
                    continue;
                }
                // Kafka 暂时不可用等临时故障：本轮结束，下一轮仍从这条开始。
                log.warn("Outbox 发送失败，本轮暂停 id={} type={}", event.getId(), event.getEventType(), sendFailure);
                return;
            }

            try {
                // 只有 Kafka 明确确认成功后才执行这一步；标记同时清掉领取租约。
                outboxStatus.markPublished(event.getId());
            }
            catch (RuntimeException databaseFailure) {
                // Kafka 已经收到了，只是数据库暂时没标上。
                // 下一轮可能再发一次，由 Consumer 的 consumed_events 去重兜底。
                log.error("Kafka 已确认，但 Outbox 标记失败 id={}", event.getId(), databaseFailure);
                return;
            }
        }
    }

    private static String workerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        }
        catch (Exception e) {
            host = "unknown";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
