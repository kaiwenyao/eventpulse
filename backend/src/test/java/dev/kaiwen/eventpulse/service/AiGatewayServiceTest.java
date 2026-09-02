package dev.kaiwen.eventpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.dto.AiDtos.AiUsage;
import dev.kaiwen.eventpulse.dto.AiDtos.CopySuggestion;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryChatRequest;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryChatResponse;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryEventRef;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryResult;
import dev.kaiwen.eventpulse.dto.AiDtos.HistoryMessage;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventRequest;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventResult;
import dev.kaiwen.eventpulse.entity.AiConversation;
import dev.kaiwen.eventpulse.entity.AiMessage;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.AiConversationRepository;
import dev.kaiwen.eventpulse.repository.AiMessageRepository;
import dev.kaiwen.eventpulse.repository.AiRequestLogRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class AiGatewayServiceTest {

    @Mock AiServiceClient client;
    @Mock AiRateLimiter rateLimiter;
    @Mock EventRepository events;
    @Mock AiConversationRepository conversations;
    @Mock AiMessageRepository messages;
    @Mock AiRequestLogRepository requestLogs;

    private AppProperties properties;
    private AiGatewayService gateway;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        registry = new SimpleMeterRegistry();
        gateway = new AiGatewayService(properties, client, rateLimiter, new JwtService(properties), events,
                new EventService(events), conversations, messages, requestLogs, registry);
        BaseContext.clear();
    }

    @AfterEach
    void clear() {
        BaseContext.clear();
    }

    private String bearer(long userId, String role) {
        return "Bearer " + new JwtService(properties).createToken(userId, role);
    }

    private static Event event(long id, String status) {
        Event event = new Event();
        event.setId(id);
        event.setTitle("Event " + id);
        event.setSummary("s");
        event.setDescription("d");
        event.setCategory("music");
        event.setCity("Shanghai");
        event.setStartsAt(Instant.now().plusSeconds(3600));
        event.setEndsAt(Instant.now().plusSeconds(7200));
        event.setPriceCents(10000);
        event.setCapacity(100);
        event.setSold(40);
        event.setOrganiserId(9L);
        event.setStatus(status);
        event.setCreatedAt(Instant.now());
        event.setUpdatedAt(Instant.now());
        return event;
    }

    // ---- 文案助手 ----

    @Test
    void improveEventReturnsSanitisedSuggestionForOrganiser() {
        BaseContext.setUserId(9L);
        BaseContext.setRole("ORGANISER");
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.improveEvent(any())).thenReturn(new ImproveEventResult(
                "r1", new CopySuggestion("t", "s", "d", "n", java.util.Arrays.asList(" ", null, "missing price info")),
                List.of("w"), "openai", "gpt-test", new AiUsage(10, 5)));

        var response = gateway.improveEvent(new ImproveEventRequest(
                null, "标题", null, "描述", "music", "Shanghai", null, null, null, null, null));

        assertThat(response.requestId()).isNotBlank();
        assertThat(response.suggestion().title()).isEqualTo("t");
        // 空白 warning 被过滤，长度被截断。
        assertThat(response.warnings()).containsExactly("w");
        assertThat(response.suggestion().warnings()).containsExactly("missing price info");
        // 调用记录落库且带 token 用量。
        verify(requestLogs).save(any());
        assertThat(registry.counter("ai.requests", "feature", "improve-event", "status", "success").count())
                .isEqualTo(1.0);
    }

    @Test
    void improveEventRejectsNonOrganiser() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("USER");
        assertThatThrownBy(() -> gateway.improveEvent(new ImproveEventRequest(
                null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("organisers");
        verify(client, never()).improveEvent(any());
    }

    @Test
    void improveEventChecksOwnershipOfExistingEvent() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("ORGANISER");
        when(events.findByIdAndOrganiserId(12L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> gateway.improveEvent(new ImproveEventRequest(
                12L, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("your own events");
    }

    @Test
    void improveEventMapsUpstreamFailureToDegradedSignal() {
        BaseContext.setUserId(9L);
        BaseContext.setRole("ORGANISER");
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.improveEvent(any())).thenThrow(new AiUnavailableException(AiServiceClient.UNAVAILABLE));
        assertThatThrownBy(() -> gateway.improveEvent(new ImproveEventRequest(
                null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(AiUnavailableException.class);
        verify(requestLogs).save(any());
        assertThat(registry.counter("ai.failures", "feature", "improve-event", "status", "failure").count())
                .isEqualTo(1.0);
    }

    @Test
    void disabledDeploymentFailsFast() {
        properties.getAi().setEnabled(false);
        BaseContext.setUserId(9L);
        BaseContext.setRole("ORGANISER");
        assertThatThrownBy(() -> gateway.improveEvent(new ImproveEventRequest(
                null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("not enabled");
    }

    // ---- 活动发现 ----

    @Test
    void guestGetsSingleTurnAnswerWithoutConversation() {
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.discoveryChat(any())).thenReturn(new DiscoveryResult(
                "r1", "找到 1 场", List.of(new DiscoveryEventRef(1L, "周六音乐")),
                List.of(), "openai", "gpt-test", new AiUsage(50, 20)));
        when(events.findById(1L)).thenReturn(Optional.of(event(1L, EventStatus.PUBLISHED)));

        DiscoveryChatResponse response = gateway.discoveryChat(
                new DiscoveryChatRequest(null, "这个周末有什么音乐活动？"), null, "1.2.3.4");

        assertThat(response.conversationId()).isNull();
        assertThat(response.events()).hasSize(1);
        assertThat(response.events().get(0).event().id()).isEqualTo(1L);
        assertThat(response.events().get(0).reason()).isEqualTo("周六音乐");
        verify(conversations, never()).save(any());
        verify(messages, never()).save(any());
    }

    @Test
    void aiToolsContextTokenAsBearerIsTreatedAsGuest() {
        // 服务间用户上下文 token（purpose=ai-tools）即使泄漏，当 Bearer 用时
        // 也必须按游客处理，不能冒充登录用户建立会话（回归：resolveUser 曾用
        // parseToken，不校验 purpose）。
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.discoveryChat(any())).thenReturn(new DiscoveryResult(
                "r1", "ok", List.of(), List.of(), "openai", "gpt-test", null));

        String contextBearer = "Bearer " + new JwtService(properties)
                .createContextToken(2L, "USER", "req-ctx", 300);
        DiscoveryChatResponse response = gateway.discoveryChat(
                new DiscoveryChatRequest(null, "找活动"), contextBearer, "1.2.3.4");

        assertThat(response.conversationId()).isNull();
        verify(conversations, never()).save(any());
        verify(messages, never()).save(any());
    }

    @Test
    void signedInUserGetsPersistentConversationAndHistory() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("USER");
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);

        AiConversation conversation = new AiConversation();
        ReflectionTestUtils.setField(conversation, "id", 7L);
        conversation.setUserId(2L);
        when(conversations.save(any())).thenReturn(conversation);

        AiMessage older = new AiMessage();
        ReflectionTestUtils.setField(older, "id", 1L);
        older.setRole(AiMessage.ROLE_USER);
        older.setContent("第一问");
        AiMessage olderReply = new AiMessage();
        ReflectionTestUtils.setField(olderReply, "id", 2L);
        olderReply.setRole(AiMessage.ROLE_ASSISTANT);
        olderReply.setContent("第一答");
        when(messages.findByConversationIdOrderByIdDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(olderReply, older)));
        when(client.discoveryChat(any())).thenReturn(new DiscoveryResult(
                "r2", "有两场", List.of(), List.of("想要免费活动吗？"), "openai", "gpt-test", null));
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = gateway.discoveryChat(
                new DiscoveryChatRequest(null, "有没有类似刚才那个、但是周日的活动？"), bearer(2L, "USER"), "1.2.3.4");

        assertThat(response.conversationId()).isEqualTo("7");
        assertThat(response.followUpQuestions()).containsExactly("想要免费活动吗？");
        verify(messages, org.mockito.Mockito.times(2)).save(any());
        // 上下文 token 是服务端签发的，客户端无法指定 userId。
        verify(client).discoveryChat(any());
        verify(requestLogs).save(any());
    }

    @Test
    void userCannotContinueForeignConversation() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("USER");
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        AiConversation foreign = new AiConversation();
        ReflectionTestUtils.setField(foreign, "id", 8L);
        foreign.setUserId(3L);
        when(conversations.findById(8L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> gateway.discoveryChat(
                new DiscoveryChatRequest("8", "继续"), bearer(2L, "USER"), "ip"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("your own conversations");
    }

    @Test
    void rateLimitReturns429() {
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(false);
        assertThatThrownBy(() -> gateway.discoveryChat(
                new DiscoveryChatRequest(null, "hi"), null, "1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus().value())
                .isEqualTo(429);
    }

    @Test
    void improveEventIsRateLimitedToo() {
        BaseContext.setUserId(9L);
        BaseContext.setRole("ORGANISER");
        // 主办方文案助手同样是付费 LLM 调用，不能没有用户级限流。
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(false);
        assertThatThrownBy(() -> gateway.improveEvent(new ImproveEventRequest(
                null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus().value())
                .isEqualTo(429);
        verify(client, never()).improveEvent(any());
    }

    @Test
    void emptyOrTooLongMessageRejected() {
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        assertThatThrownBy(() -> gateway.discoveryChat(new DiscoveryChatRequest(null, "   "), null, "ip"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway.discoveryChat(
                new DiscoveryChatRequest(null, "x".repeat(properties.getAi().getMaxMessageChars() + 1)), null, "ip"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void fabricatedOrUnlistedEventIdsAreDropped() {
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.discoveryChat(any())).thenReturn(new DiscoveryResult(
                "r3", "结果", java.util.Arrays.asList(
                        new DiscoveryEventRef(1L, "ok"),
                        new DiscoveryEventRef(99L, "fabricated"),
                        new DiscoveryEventRef(2L, "cancelled"),
                        new DiscoveryEventRef(3L, "finished"),
                        null),
                List.of(), "openai", "m", null));
        when(events.findById(1L)).thenReturn(Optional.of(event(1L, EventStatus.PUBLISHED)));
        when(events.findById(2L)).thenReturn(Optional.of(event(2L, EventStatus.CANCELLED)));
        when(events.findById(3L)).thenReturn(Optional.of(event(3L, EventStatus.FINISHED)));
        when(events.findById(99L)).thenReturn(Optional.empty());

        var response = gateway.discoveryChat(new DiscoveryChatRequest(null, "找活动"), null, "ip");

        assertThat(response.events()).hasSize(1);
        assertThat(response.events().get(0).event().id()).isEqualTo(1L);
    }

    @Test
    void overLimitEventsAreTruncatedToConfiguredMaximum() {
        properties.getAi().setMaxEvents(2);
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.discoveryChat(any())).thenReturn(new DiscoveryResult(
                "r4", "结果", List.of(
                        new DiscoveryEventRef(1L, "a"),
                        new DiscoveryEventRef(2L, "b"),
                        new DiscoveryEventRef(3L, "c")),
                List.of(), "openai", "m", null));
        when(events.findById(any())).thenAnswer(inv -> Optional.of(event(inv.getArgument(0), EventStatus.PUBLISHED)));

        var response = gateway.discoveryChat(new DiscoveryChatRequest(null, "找活动"), null, "ip");
        assertThat(response.events()).hasSize(2);
    }

    @Test
    void upstreamFailureIsRecordedAndRethrown() {
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.discoveryChat(any())).thenThrow(new AiUnavailableException(AiServiceClient.UNAVAILABLE));

        assertThatThrownBy(() -> gateway.discoveryChat(new DiscoveryChatRequest(null, "找活动"), null, "ip"))
                .isInstanceOf(AiUnavailableException.class);
        verify(requestLogs).save(any());
        assertThat(registry.counter("ai.failures", "feature", "discovery", "status", "failure").count())
                .isEqualTo(1.0);
    }

    @Test
    void historyIsTrimmedToConfiguredLimitAndReversedToChronological() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("USER");
        properties.getAi().setHistoryLimit(2);
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(conversations.save(any())).thenAnswer(inv -> {
            AiConversation c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", 5L);
            return c;
        });
        AiMessage m1 = new AiMessage();
        ReflectionTestUtils.setField(m1, "id", 1L);
        m1.setRole(AiMessage.ROLE_USER);
        m1.setContent("q1");
        AiMessage m2 = new AiMessage();
        ReflectionTestUtils.setField(m2, "id", 2L);
        m2.setRole(AiMessage.ROLE_ASSISTANT);
        m2.setContent("a1");
        AiMessage m3 = new AiMessage();
        ReflectionTestUtils.setField(m3, "id", 3L);
        m3.setRole(AiMessage.ROLE_USER);
        m3.setContent("q2");
        when(messages.findByConversationIdOrderByIdDesc(eq(5L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(m3, m2)));
        List<HistoryMessage> seenHistory = new java.util.ArrayList<>();
        when(client.discoveryChat(any())).thenAnswer(inv -> {
            var payload = (dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload) inv.getArgument(0);
            seenHistory.addAll(payload.history());
            return new DiscoveryResult("r", "ok", List.of(), List.of(), "p", "m", null);
        });
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));

        gateway.discoveryChat(new DiscoveryChatRequest(null, "第三问"), bearer(2L, "USER"), "ip");

        assertThat(seenHistory).hasSize(2);
        assertThat(seenHistory.get(0).content()).isEqualTo("a1");
        assertThat(seenHistory.get(1).content()).isEqualTo("q2");
        verify(messages, org.mockito.Mockito.times(2)).save(any());
    }

}
