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
        List<AiConversation> batch = conversations.findByUpdatedAtBefore(cutoff,
                PageRequest.of(0, properties.getAi().getRetentionBatchSize(), Sort.by("id").ascending()))
                .getContent();
        if (batch.isEmpty()) {
            return 0;
        }
        List<Long> ids = batch.stream().map(AiConversation::getId).toList();
        int removedMessages = messages.deleteByConversationIdIn(ids);
        int removedConversations = conversations.deleteByIdIn(ids);
        meters.counter("ai.retention", "result", "messages").increment(removedMessages);
        meters.counter("ai.retention", "result", "conversations").increment(removedConversations);
        return removedConversations;
    }

    private int purgeRequestLogs() {
        int retentionDays = properties.getAi().getRequestLogRetentionDays();
        if (retentionDays <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        List<AiRequestLog> batch = requestLogs.findByCreatedAtBefore(cutoff,
                PageRequest.of(0, properties.getAi().getRetentionBatchSize(), Sort.by("createdAt").ascending()))
                .getContent();
        if (batch.isEmpty()) {
            return 0;
        }
        int removed = requestLogs.deleteByRequestIdIn(batch.stream().map(AiRequestLog::getRequestId).toList());
        meters.counter("ai.retention", "result", "requests").increment(removed);
        return removed;
    }
}
