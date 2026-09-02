package dev.kaiwen.eventpulse.outbox;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.kaiwen.eventpulse.entity.OutboxEvent;
import dev.kaiwen.eventpulse.outbox.OutboxStatusService.FailureAction;
import dev.kaiwen.eventpulse.repository.OutboxRepository;

/**
 * 把 Outbox 里待发送的消息发给 Kafka，并且：
 * 1. 等 Kafka 明确确认成功后才标记 published_at（不提前宣布成功）；
 * 2. Kafka 发送失败与数据库标记失败分开处理，数据库失败不会被误记成发送失败；
 * 3. 明确是消息本身问题的坏消息隔离（failed_at）后继续处理后面的消息；
 * 4. 明确的临时故障（如 Kafka 不可用）结束本轮，下一轮从同一条继续，保持顺序。
 */
@Component
public class OutboxRelay {

    /** 等待 Kafka 发送结果的秒数；可配置，超时测试可以把它调小。 */
    public static final String FUTURE_WAIT_SECONDS_PROPERTY = "eventpulse.outbox.future-wait-seconds";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxStatusService outboxStatus;
    private final long futureWaitSeconds;

    public OutboxRelay(
            OutboxRepository outbox,
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxStatusService outboxStatus,
            @Value("${eventpulse.outbox.future-wait-seconds:12}") long futureWaitSeconds) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.outboxStatus = outboxStatus;
        this.futureWaitSeconds = futureWaitSeconds;
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        for (OutboxEvent event : outbox.findTop50ByPublishedAtIsNullAndFailedAtIsNullOrderByIdAsc()) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getDedupKey(), event.getPayload())
                        .get(futureWaitSeconds, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Outbox relay 被中断，提前结束本轮 id={}", event.getId());
                return;
            }
            catch (Exception sendFailure) {
                FailureAction action = outboxStatus.recordPublishFailure(event.getId(), sendFailure);
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
                // 只有 Kafka 明确确认成功后才执行这一步。
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

    /** 待发送数：published_at 为空且未被隔离。 */
    public long pending() {
        return outbox.countByPublishedAtIsNullAndFailedAtIsNull();
    }

    /** 已隔离数：等待人工检查后恢复。 */
    public long failed() {
        return outbox.countByFailedAtIsNotNull();
    }

    /** 最老的待发送消息已经等了多久（秒）。没有待发送消息时返回 0，避免监控字段为 null。 */
    public Double oldestPendingAgeSeconds() {
        Double age = outbox.secondsSinceOldestPending();
        return age == null ? 0d : age;
    }
}