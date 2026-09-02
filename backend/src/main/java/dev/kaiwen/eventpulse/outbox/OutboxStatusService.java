package dev.kaiwen.eventpulse.outbox;

import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;

import dev.kaiwen.eventpulse.repository.OutboxRepository;

/**
 * 用尽量短的事务更新 Outbox 状态：
 * - 成功发送：条件 UPDATE 打 published_at，不先查询实体
 * - 记录发送失败：递增 publish_attempts、记录 last_error、必要时写 failed_at
 *
 * 发送 Kafka 与这些数据库操作严格分离，等待 Kafka 的 12 秒不占用数据库事务，
 * 且数据库临时故障不会被误记成 Kafka 发送失败。
 */
@Service
public class OutboxStatusService {

    /**
     * 无法分类的失败连续出现多少次后隔离。仅用于 UNKNOWN 分支；
     * 明确的临时故障（Kafka 不可用、整体超时、认证配置错误）不使用次数上限。
     */
    static final int UNKNOWN_FAILURE_LIMIT = 5;

    private static final Logger log = LoggerFactory.getLogger(OutboxStatusService.class);

    private final OutboxRepository outbox;

    public OutboxStatusService(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    public enum FailureAction {
        /** 暂时失败，本轮结束，下一轮继续从这条开始重试。 */
        RETRY_LATER,
        /** 消息本身有问题，已隔离，本轮继续处理后面的消息。 */
        QUARANTINED
    }

    /**
     * 领取一批待发送消息（原子 UPDATE，见 OutboxRepository.claimBatch）。
     * @Modifying 查询必须在事务里执行：由这里的 @Transactional 提供短事务，
     * Relay 本身不开事务（Kafka 等待的 12 秒不能占着数据库事务）。
     */
    @Transactional
    public int claimBatch(String token, Instant until, Instant now, int batch) {
        return outbox.claimBatch(token, until, now, batch);
    }

    /** 发送前给整批续租：Worker 活着租约就不过期，多 Worker 不会重复处理同一条。 */
    @Transactional
    public void renewClaim(String token, Instant until) {
        outbox.renewClaim(token, until);
    }

    /** 一轮结束时释放本批剩余租约，其余消息立即可以被任意 Worker 重新领取。 */
    @Transactional
    public void releaseAllClaims(String token) {
        outbox.releaseAllClaims(token);
    }

    /**
     * Kafka 明确确认成功后标记已发布。条件 UPDATE 返回 0 行时
     * 只表示「已经没有需要更新的行」，不抛异常、不阻塞 Relay。
     */
    @Transactional
    public void markPublished(Long id) {
        int updated = outbox.markPublished(id, Instant.now());
        if (updated == 0) {
            log.warn("Outbox 已经标记过或不存在 id={}", id);
        }
    }

    /** 待发送数：published_at 为空且未被隔离。API 的主办方 Dashboard 用它观察积压。 */
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

    /**
     * 记录一次发送失败，并决定下一步动作。先拆开 Future 外层包装
     * （ExecutionException / CompletionException）再按异常分类。
     */
    @Transactional
    public FailureAction recordPublishFailure(Long id, Throwable failure) {
        Throwable cause = unwrap(failure);
        Instant failedAt = null;
        switch (classify(cause)) {
            case PERMANENT -> {
                // 消息本身的问题：payload 太大、序列化失败、非法 topic。
                // 重试一万次也没用，立刻隔离，不堵住后面的消息。
                failedAt = Instant.now();
                outbox.recordFailure(id, messageOf(cause), failedAt);
            }
            case TRANSIENT -> {
                // Kafka 暂时不可用（RetriableException、超时、认证、权限等整体问题）。
                // 保持待发送，本轮结束下一轮重试；明确的临时故障不使用次数上限。
                outbox.recordFailure(id, messageOf(cause), null);
                return FailureAction.RETRY_LATER;
            }
            case UNKNOWN -> {
                // 无法分类的错误：连续出现 5 次后隔离，避免无限重试，
                // 也避免正常的暂时抖动把好消息批量误隔离。
                int attempts = outbox.findById(id)
                        .map(e -> e.getPublishAttempts() + 1)
                        .orElse(1);
                if (attempts >= UNKNOWN_FAILURE_LIMIT) {
                    failedAt = Instant.now();
                }
                outbox.recordFailure(id, messageOf(cause), failedAt);
            }
        }
        return failedAt == null ? FailureAction.RETRY_LATER : FailureAction.QUARANTINED;
    }

    private enum Kind {
        PERMANENT, TRANSIENT, UNKNOWN
    }

    private static Kind classify(Throwable cause) {
        if (cause instanceof RetriableException) {
            return Kind.TRANSIENT;
        }
        String name = cause.getClass().getName();
        if (cause instanceof SerializationException
                || name.contains("RecordTooLarge")
                || name.contains("InvalidTopic")) {
            // 消息本身的问题：payload 太大、序列化失败、非法 topic。
            // 重试一万次也没用，立刻隔离，不堵住后面的消息。
            return Kind.PERMANENT;
        }
        if (name.contains("Timeout") || name.contains("Interrupted")
                || name.contains("Authentication") || name.contains("Authorizer")
                || name.contains("KafkaException")) {
            // Kafka 暂时不可用、超时、认证、权限等整体配置问题：
            // 保持待发送，下一轮重试，不使用次数上限。
            return Kind.TRANSIENT;
        }
        return Kind.UNKNOWN;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof ExecutionException || current instanceof CompletionException) {
            Throwable cause = current.getCause();
            if (cause == null) {
                break;
            }
            current = cause;
        }
        return current;
    }

    private static String messageOf(Throwable cause) {
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}