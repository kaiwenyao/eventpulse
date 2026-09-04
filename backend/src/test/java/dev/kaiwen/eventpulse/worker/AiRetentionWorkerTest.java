package dev.kaiwen.eventpulse.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.entity.AiConversation;
import dev.kaiwen.eventpulse.entity.AiRequestLog;
import dev.kaiwen.eventpulse.repository.AiConversationRepository;
import dev.kaiwen.eventpulse.repository.AiMessageRepository;
import dev.kaiwen.eventpulse.repository.AiRequestLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiRetentionWorkerTest {

    private AiConversationRepository conversations;
    private AiMessageRepository messages;
    private AiRequestLogRepository requestLogs;
    private AppProperties properties;
    private SimpleMeterRegistry meters;
    private AiRetentionWorker worker;

    @BeforeEach
    void setUp() {
        conversations = mock(AiConversationRepository.class);
        messages = mock(AiMessageRepository.class);
        requestLogs = mock(AiRequestLogRepository.class);
        properties = new AppProperties();
        properties.getAi().setRetentionBatchSize(2);
        meters = new SimpleMeterRegistry();
        worker = new AiRetentionWorker(conversations, messages, requestLogs, properties, meters);
    }

    private static AiConversation conversation(long id) {
        AiConversation conversation = new AiConversation();
        ReflectionTestUtils.setField(conversation, "id", id);
        conversation.setUserId(1L);
        conversation.setKind("discovery");
        return conversation;
    }

    private static AiRequestLog requestLog(String id) {
        AiRequestLog entry = new AiRequestLog();
        entry.setRequestId(id);
        return entry;
    }

    @Test
    void deletesMessagesBeforeConversationsAndCountsBothBatches() {
        // batchSize=2，第一批满员 → 会继续查下一批；第二批空则收工。
        when(conversations.findByUpdatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(conversation(1L), conversation(2L))))
                .thenReturn(new PageImpl<>(List.of()));
        when(messages.deleteByConversationIdIn(anyList())).thenReturn(5);
        when(conversations.deleteByIdIn(anyList())).thenReturn(2);
        when(requestLogs.findByCreatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        worker.purge();

        // ai_messages.conversation_id 是普通外键，没有 ON DELETE CASCADE：
        // 顺序反了会直接违反约束。
        var order = org.mockito.Mockito.inOrder(messages, conversations);
        order.verify(messages).deleteByConversationIdIn(List.of(1L, 2L));
        order.verify(conversations).deleteByIdIn(List.of(1L, 2L));
        assertThat(meters.counter("ai.retention", "result", "messages").count()).isEqualTo(5.0);
        assertThat(meters.counter("ai.retention", "result", "conversations").count()).isEqualTo(2.0);
    }

    @Test
    void batchesAreBoundedAndOrderedSoEachRoundMakesProgress() {
        when(conversations.findByUpdatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(conversation(1L))));
        when(requestLogs.findByCreatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        worker.purge();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(conversations).findByUpdatedAtBefore(any(), pageable.capture());
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 2, Sort.by("id").ascending()));
    }

    @Test
    void cutoffFollowsTheConfiguredRetentionWindow() {
        properties.getAi().setRetentionDays(90);
        when(conversations.findByUpdatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(requestLogs.findByCreatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        worker.purge();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(conversations).findByUpdatedAtBefore(cutoff.capture(), any(Pageable.class));
        assertThat(cutoff.getValue()).isBefore(Instant.now().minusSeconds(89L * 86400));
        assertThat(cutoff.getValue()).isAfter(Instant.now().minusSeconds(91L * 86400));
    }

    @Test
    void emptyBatchDeletesNothing() {
        when(conversations.findByUpdatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(requestLogs.findByCreatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        worker.purge();

        verify(messages, never()).deleteByConversationIdIn(anyList());
        verify(conversations, never()).deleteByIdIn(anyList());
        verify(requestLogs, never()).deleteByRequestIdIn(anyList());
    }

    @Test
    void zeroRetentionDaysDisablesThatHalfOfTheCleanup() {
        properties.getAi().setRetentionDays(0);
        properties.getAi().setRequestLogRetentionDays(0);

        worker.purge();

        verify(conversations, never()).findByUpdatedAtBefore(any(), any(Pageable.class));
        verify(requestLogs, never()).findByCreatedAtBefore(any(), any(Pageable.class));
    }

    @Test
    void requestLogsArePurgedOnTheirOwnWindow() {
        properties.getAi().setRetentionDays(0);
        properties.getAi().setRequestLogRetentionDays(180);
        when(requestLogs.findByCreatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(requestLog("a"), requestLog("b"))))
                .thenReturn(new PageImpl<>(List.of()));
        when(requestLogs.deleteByRequestIdIn(anyList())).thenReturn(2);

        worker.purge();

        verify(requestLogs).deleteByRequestIdIn(List.of("a", "b"));
        assertThat(meters.counter("ai.retention", "result", "requests").count()).isEqualTo(2.0);
    }
    @Test
    void oneRunKeepsDrainingUntilTheBacklogIsGone() {
        // 默认 24 小时才跑一轮：如果一轮只清一批（200 行），排空速率就是 200 行/天，
        // 任何产出更快的部署都会永久积压，数据实际上永远留在保留期之外。
        when(conversations.findByUpdatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(conversation(1L), conversation(2L))))
                .thenReturn(new PageImpl<>(List.of(conversation(3L), conversation(4L))))
                .thenReturn(new PageImpl<>(List.of(conversation(5L))));
        when(conversations.deleteByIdIn(anyList())).thenReturn(2, 2, 1);
        when(requestLogs.findByCreatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        worker.purge();

        // 三批一次跑完；最后一批不满 batchSize，不再多查一次。
        verify(conversations, org.mockito.Mockito.times(3))
                .findByUpdatedAtBefore(any(), any(Pageable.class));
        assertThat(meters.counter("ai.retention", "result", "conversations").count()).isEqualTo(5.0);
    }

    @Test
    void oneRunIsStillBoundedSoItCannotHogTheTransactionForever() {
        // 删除没生效（例如并发下别的实例先删了）时，第 0 页会一直返回同一批：
        // 必须有上限，否则单轮永远跑不完。
        when(conversations.findByUpdatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(conversation(1L), conversation(2L))));
        when(conversations.deleteByIdIn(anyList())).thenReturn(0);
        when(requestLogs.findByCreatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        worker.purge();

        verify(conversations, org.mockito.Mockito.times(50))
                .findByUpdatedAtBefore(any(), any(Pageable.class));
    }

}
