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
        when(conversations.findByUpdatedAtBefore(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(conversation(1L), conversation(2L))));
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
                .thenReturn(new PageImpl<>(List.of(requestLog("a"), requestLog("b"))));
        when(requestLogs.deleteByRequestIdIn(anyList())).thenReturn(2);

        worker.purge();

        verify(requestLogs).deleteByRequestIdIn(List.of("a", "b"));
        assertThat(meters.counter("ai.retention", "result", "requests").count()).isEqualTo(2.0);
    }
}
