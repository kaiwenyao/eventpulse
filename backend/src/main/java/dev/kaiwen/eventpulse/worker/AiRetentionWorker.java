package dev.kaiwen.eventpulse.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.entity.AiConversation;
import dev.kaiwen.eventpulse.entity.AiRequestLog;
import dev.kaiwen.eventpulse.repository.AiConversationRepository;
import dev.kaiwen.eventpulse.repository.AiMessageRepository;
import dev.kaiwen.eventpulse.repository.AiRequestLogRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * AI 数据保留期清理。
 *
 * ai_conversations / ai_messages / ai_requests 原本永不清理：既是无限增长，也是
 * GDPR 意义上说不清的留存。这里按配置的窗口分批删除，每轮上限
 * eventpulse.ai.retention-batch-size，剩下的留给下一轮 —— 宁可清得慢，也不要
 * 一次删爆事务。
 *
 * 顺序是刻意的：ai_messages.conversation_id 是普通外键、没有 ON DELETE CASCADE，
 * 必须先删消息再删会话。
 */
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "eventpulse.ai", name = "retention-enabled", havingValue = "true",
        matchIfMissing = true)
public class AiRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(AiRetentionWorker.class);

    /**
     * 单轮最多处理多少批。默认 24 小时才跑一次，如果一轮只清一批（200 行），排空
     * 速率就是 200 行/天 —— 任何一天过期行数超过这个量的部署，积压会永久增长，
     * 数据实际上永远留在保留期之外。所以一轮要连续清到没有为止，只保留一个上限，
     * 避免单次运行无限期占着事务。
     */
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final AiRequestLogRepository requestLogs;
    private final AppProperties properties;
    private final MeterRegistry meters;

    public AiRetentionWorker(AiConversationRepository conversations, AiMessageRepository messages,
                             AiRequestLogRepository requestLogs, AppProperties properties, MeterRegistry meters) {
        this.conversations = conversations;
        this.messages = messages;
        this.requestLogs = requestLogs;
        this.properties = properties;
        this.meters = meters;
    }

    @Scheduled(
            fixedDelayString = "${eventpulse.ai.retention-fixed-delay-ms:86400000}",
            initialDelayString = "${eventpulse.ai.retention-initial-delay-ms:120000}")
    @Transactional
    public void purge() {
        int conversationsDeleted = purgeConversations();
        int logsDeleted = purgeRequestLogs();
        if (conversationsDeleted > 0 || logsDeleted > 0) {
            log.info("AI retention round: {} conversations, {} request logs removed",
                    conversationsDeleted, logsDeleted);
        }
    }

    private int purgeConversations() {
        int retentionDays = properties.getAi().getRetentionDays();
        if (retentionDays <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int batchSize = properties.getAi().getRetentionBatchSize();
        int removedTotal = 0;
        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            // 每轮都取第 0 页：上一批已经删掉了，第 0 页就是下一批待清理的行。
            List<AiConversation> batch = conversations.findByUpdatedAtBefore(cutoff,
                    PageRequest.of(0, batchSize, Sort.by("id").ascending())).getContent();
            if (batch.isEmpty()) {
                break;
            }
            List<Long> ids = batch.stream().map(AiConversation::getId).toList();
            int removedMessages = messages.deleteByConversationIdIn(ids);
            int removedConversations = conversations.deleteByIdIn(ids);
            meters.counter("ai.retention", "result", "messages").increment(removedMessages);
            meters.counter("ai.retention", "result", "conversations").increment(removedConversations);
            removedTotal += removedConversations;
            if (batch.size() < batchSize) {
                // 不满一批说明已经清空，不必再多查一次。
                break;
            }
        }
        return removedTotal;
    }

    private int purgeRequestLogs() {
        int retentionDays = properties.getAi().getRequestLogRetentionDays();
        if (retentionDays <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int batchSize = properties.getAi().getRetentionBatchSize();
        int removedTotal = 0;
        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            List<AiRequestLog> batch = requestLogs.findByCreatedAtBefore(cutoff,
                    PageRequest.of(0, batchSize, Sort.by("createdAt").ascending())).getContent();
            if (batch.isEmpty()) {
                break;
            }
            int removed = requestLogs.deleteByRequestIdIn(
                    batch.stream().map(AiRequestLog::getRequestId).toList());
            meters.counter("ai.retention", "result", "requests").increment(removed);
            removedTotal += removed;
            if (batch.size() < batchSize) {
                break;
            }
        }
        return removedTotal;
    }
}
