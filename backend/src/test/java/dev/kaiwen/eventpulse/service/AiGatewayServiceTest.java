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
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import dev.kaiwen.eventpulse.repository.UserPreferenceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class AiGatewayServiceTest {

    @Mock AiServiceClient client;
    @Mock AiRateLimiter rateLimiter;
    @Mock EventRepository events;
    @Mock AiConversationRepository conversations;
    @Mock AiMessageRepository messages;
    @Mock AiRequestLogRepository requestLogs;
    @Mock UserPreferenceRepository preferences;

    private AppProperties properties;
    private AiGatewayService gateway;
    private SimpleMeterRegistry registry;
    private AiTokenBudget budget;
    private AiResponseCache cache;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        registry = new SimpleMeterRegistry();
        // 预算与缓存都用真对象：没有注入 Redis 时预算走本地计数、缓存 isAvailable()
        // 为 false（等于关闭），既有用例的行为因此完全不变。
        budget = new AiTokenBudget(properties);
        cache = new AiResponseCache(new ObjectMapper(), registry);
        gateway = new AiGatewayService(properties, client, rateLimiter, new JwtService(properties), events,
                new EventService(events), conversations, messages, requestLogs, preferences,
                budget, cache, registry);
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
                null, "标题", null, "描述", "music", "Shanghai", null, null, null, null, null, null));

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
                null, null, null, null, null, null, null, null, null, null, null, null)))
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
                12L, null, null, null, null, null, null, null, null, null, null, null)))
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
                null, null, null, null, null, null, null, null, null, null, null, null)))
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
                null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("not enabled");
    }

    // ---- 活动复核：批量查询与上限 ----

    @Test
    @SuppressWarnings("unchecked")
    void referencedEventsAreVerifiedInOneQueryWithADedupedCappedIdList() {
        properties.getAi().setMaxEvents(3);
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        // 上游可能返回重复 id，也可能返回远超上限的一大串。
        List<DiscoveryEventRef> refs = new java.util.ArrayList<>();
        refs.add(new DiscoveryEventRef(1L, "a"));
        refs.add(new DiscoveryEventRef(1L, "重复"));
        for (long id = 2; id <= 50; id++) {
            refs.add(new DiscoveryEventRef(id, "x"));
        }
        when(client.discoveryChat(any())).thenReturn(new DiscoveryResult(
                "r1", "ok", refs, List.of(), "openai", "gpt-test", null));
        when(events.findAllById(any())).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            List<Event> found = new java.util.ArrayList<>();
            ids.forEach(id -> found.add(event(id, EventStatus.PUBLISHED)));
            return found;
        });

        var response = gateway.discoveryChat(new DiscoveryChatRequest(null, "找活动"), null, "1.2.3.4");

        // 一次批量查，不是每个 id 一次（原来是 N+1）。
        ArgumentCaptor<Iterable<Long>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(events, org.mockito.Mockito.times(1)).findAllById(ids.capture());
        List<Long> queried = new java.util.ArrayList<>();
        ids.getValue().forEach(queried::add);
        // 去重 + 截断到上限：批量查没了循环里的 break，不显式截断就会变成巨大的 IN。
        assertThat(queried).containsExactly(1L, 2L, 3L);
        assertThat(response.events()).hasSize(3);
    }

    @Test
    void finishedEventsStayOutEvenThoughTheyArePubliclyListed() {
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.discoveryChat(any())).thenReturn(new DiscoveryResult(
                "r1", "ok", List.of(new DiscoveryEventRef(3L, "已结束")), List.of(),
                "openai", "gpt-test", null));
        when(events.findAllById(any())).thenReturn(List.of(event(3L, EventStatus.FINISHED)));

        var response = gateway.discoveryChat(new DiscoveryChatRequest(null, "找活动"), null, "1.2.3.4");

        // FINISHED 在 PUBLIC_LIST 里（结束的活动仍可浏览），但不该再被推荐。
        assertThat(EventStatus.PUBLIC_LIST).contains(EventStatus.FINISHED);
        assertThat(response.events()).isEmpty();
    }

    // ---- 会话生命周期 ----

    private static AiConversation conversationOf(long id, long userId) {
        AiConversation conversation = new AiConversation();
        ReflectionTestUtils.setField(conversation, "id", id);
        conversation.setUserId(userId);
        conversation.setKind("discovery");
        return conversation;
    }

    private static AiMessage messageOf(long id, String role, String content) {
        AiMessage message = new AiMessage();
        ReflectionTestUtils.setField(message, "id", id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    @Test
    void conversationListPreviewsTheLatestMessage() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("USER");
        when(conversations.findByUserIdAndKindOrderByUpdatedAtDesc(eq(2L), eq("discovery"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(conversationOf(7L, 2L))));
        when(messages.findByConversationIdOrderByIdDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(messageOf(2L, AiMessage.ROLE_ASSISTANT, "找到 3 场爵士演出"))));

        var list = gateway.listConversations();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo("7");
        assertThat(list.get(0).preview()).isEqualTo("找到 3 场爵士演出");
    }

    @Test
    void conversationWithoutMessagesPreviewsAsEmptyRatherThanNull() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("USER");
        when(conversations.findByUserIdAndKindOrderByUpdatedAtDesc(eq(2L), eq("discovery"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(conversationOf(7L, 2L))));
        when(messages.findByConversationIdOrderByIdDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(gateway.listConversations().get(0).preview()).isEmpty();
    }

    @Test
    void conversationDetailReturnsTextOnlyHistoryInChronologicalOrder() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("USER");
        when(conversations.findById(7L)).thenReturn(Optional.of(conversationOf(7L, 2L)));
        when(messages.findByConversationIdOrderByIdAsc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        messageOf(1L, AiMessage.ROLE_USER, "第一问"),
                        messageOf(2L, AiMessage.ROLE_ASSISTANT, "第一答"))));

        var detail = gateway.getConversation("7");

        assertThat(detail.id()).isEqualTo("7");
        assertThat(detail.messages()).extracting("content").containsExactly("第一问", "第一答");
    }

    @Test
    void conversationsOfOtherUsersAreForbiddenNotMerelyEmpty() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("USER");
        when(conversations.findById(7L)).thenReturn(Optional.of(conversationOf(7L, 999L)));

        assertThatThrownBy(() -> gateway.getConversation("7"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("your own conversations");
        assertThatThrownBy(() -> gateway.deleteConversation("7"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("your own conversations");
    }

    @Test
    void missingOrMalformedConversationIdIsRejected() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("USER");
        when(conversations.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateway.getConversation("7"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> gateway.getConversation("abc"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid conversation id");
    }

    @Test
    void deletingAConversationRemovesItsMessagesFirst() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("USER");
        when(conversations.findById(7L)).thenReturn(Optional.of(conversationOf(7L, 2L)));

        gateway.deleteConversation("7");

        var order = org.mockito.Mockito.inOrder(messages, conversations);
        order.verify(messages).deleteByConversationIdIn(List.of(7L));
        order.verify(conversations).deleteByIdIn(List.of(7L));
    }

    @Test
    void conversationEndpointsRequireASignedInUser() {
        assertThatThrownBy(() -> gateway.listConversations())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sign in");
    }

    // ---- 成本：预算与缓存 ----

    /** 给缓存接上一个 Map 冒充的 Redis，让 isAvailable() 为真。 */
    private Map<String, String> enableCache() {
        Map<String, String> store = new java.util.HashMap<>();
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(values);
        org.mockito.Mockito.when(values.get(anyString()))
                .thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        cache.setRedis(redis);
        return store;
    }

    private static ImproveEventRequest improveRequest(Boolean refresh) {
        return new ImproveEventRequest(null, "标题", null, "描述", "music", "Shanghai",
                null, null, null, null, null, refresh);
    }

    private static ImproveEventResult improveResult(String title) {
        return new ImproveEventResult("upstream-id", new CopySuggestion(title, "s", "d", "n", List.of()),
                List.of(), "openai", "gpt-test", new AiUsage(10, 5));
    }

    @Test
    void exhaustedDailyBudgetIsRejectedWithoutCallingTheModel() {
        BaseContext.setUserId(9L);
        BaseContext.setRole("ORGANISER");
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        properties.getAi().setDailyTokenBudgetUser(10);
        budget.record(9L, 10, 0);

        assertThatThrownBy(() -> gateway.improveEvent(improveRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("daily AI budget");
        verify(client, never()).improveEvent(any());
        assertThat(registry.counter("ai.budget.rejected", "feature", "improve-event", "status", "rejected").count())
                .isEqualTo(1.0);
    }

    @Test
    void successfulCallChargesTheBudgetAndRecordsTokenMetrics() {
        BaseContext.setUserId(9L);
        BaseContext.setRole("ORGANISER");
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.improveEvent(any())).thenReturn(improveResult("t"));
        properties.getAi().setDailyTokenBudgetUser(12);

        gateway.improveEvent(improveRequest(null));

        assertThat(registry.counter("ai.tokens", "feature", "improve-event", "kind", "input").count())
                .isEqualTo(10.0);
        assertThat(registry.counter("ai.tokens", "feature", "improve-event", "kind", "output").count())
                .isEqualTo(5.0);
        assertThat(registry.timer("ai.latency", "feature", "improve-event").count()).isEqualTo(1L);
        // 15 tokens 已经超过 12 的上限：下一次被挡住。
        assertThat(budget.hasBudget(9L)).isFalse();
    }

    @Test
    void upstreamFailureStillChargesAPenaltySoFailuresAreNotFree() {
        BaseContext.setUserId(9L);
        BaseContext.setRole("ORGANISER");
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.improveEvent(any())).thenThrow(new AiUnavailableException(AiServiceClient.UNAVAILABLE));
        properties.getAi().setDailyTokenBudgetUser(1000);
        properties.getAi().setFailureTokenPenalty(1000);

        assertThatThrownBy(() -> gateway.improveEvent(improveRequest(null)))
                .isInstanceOf(AiUnavailableException.class);
        // 失败的那一轮真的烧了 token：不记账的话，能稳定触发失败的用户就等于没有预算。
        assertThat(budget.hasBudget(9L)).isFalse();
    }

    @Test
    void improveEventServesTheCacheWithAFreshRequestIdAndSkipsTheModel() {
        BaseContext.setUserId(9L);
        BaseContext.setRole("ORGANISER");
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.improveEvent(any())).thenReturn(improveResult("第一次生成"));
        enableCache();

        var first = gateway.improveEvent(improveRequest(null));
        var second = gateway.improveEvent(improveRequest(null));

        assertThat(second.suggestion().title()).isEqualTo("第一次生成");
        // 第二次没有再调模型。
        verify(client, org.mockito.Mockito.times(1)).improveEvent(any());
        // requestId 必须是这一次新生成的，否则它对不上任何 ai_requests 行。
        assertThat(second.requestId()).isNotEqualTo(first.requestId());
        assertThat(second.requestId()).isNotEqualTo("upstream-id");
        // 命中缓存不写调用日志、不计 ai.requests。
        verify(requestLogs, org.mockito.Mockito.times(1)).save(any());
        assertThat(registry.counter("ai.cache", "feature", "improve-event", "result", "hit").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("ai.requests", "feature", "improve-event", "status", "success").count())
                .isEqualTo(1.0);
    }

    @Test
    void regenerateBypassesTheCacheSoTheButtonActuallyDoesSomething() {
        BaseContext.setUserId(9L);
        BaseContext.setRole("ORGANISER");
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.improveEvent(any()))
                .thenReturn(improveResult("第一稿"))
                .thenReturn(improveResult("第二稿"));
        enableCache();

        gateway.improveEvent(improveRequest(null));
        var regenerated = gateway.improveEvent(improveRequest(true));

        assertThat(regenerated.suggestion().title()).isEqualTo("第二稿");
        verify(client, org.mockito.Mockito.times(2)).improveEvent(any());
        // 重新生成仍然写回缓存：下一次不带 refresh 的请求拿到的是最新那份。
        var afterwards = gateway.improveEvent(improveRequest(null));
        assertThat(afterwards.suggestion().title()).isEqualTo("第二稿");
        verify(client, org.mockito.Mockito.times(2)).improveEvent(any());
    }

    @Test
    void guestDiscoveryIsCachedButSignedInUsersAreNot() {
        enableCache();
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.discoveryChat(any())).thenReturn(new DiscoveryResult(
                "r1", "找到 1 场", List.of(new DiscoveryEventRef(1L, "周六音乐")),
                List.of(), "openai", "gpt-test", new AiUsage(50, 20)));
        when(events.findAllById(any())).thenReturn(List.of(event(1L, EventStatus.PUBLISHED)));

        gateway.discoveryChat(new DiscoveryChatRequest(null, "有什么热门活动？"), null, "1.2.3.4");
        DiscoveryChatResponse cached = gateway.discoveryChat(
                new DiscoveryChatRequest(null, "有什么热门活动？"), null, "1.2.3.4");

        verify(client, org.mockito.Mockito.times(1)).discoveryChat(any());
        // 命中缓存时活动卡片仍然重新复核可见性，所以照样查了库。
        assertThat(cached.events()).hasSize(1);
        assertThat(registry.counter("ai.cache", "feature", "discovery", "result", "hit").count())
                .isEqualTo(1.0);

        // 登录用户带着签名上下文，Python 侧会注册个人化工具，答案与身份相关：不共享。
        AiConversation conversation = new AiConversation();
        ReflectionTestUtils.setField(conversation, "id", 7L);
        conversation.setUserId(2L);
        when(conversations.save(any())).thenReturn(conversation);
        when(messages.findByConversationIdOrderByIdDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        gateway.discoveryChat(new DiscoveryChatRequest(null, "有什么热门活动？"), bearer(2L, "USER"), "1.2.3.4");
        verify(client, org.mockito.Mockito.times(2)).discoveryChat(any());
    }

    @Test
    void cachedGuestAnswerDropsEventsThatAreNoLongerPublic() {
        enableCache();
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.discoveryChat(any())).thenReturn(new DiscoveryResult(
                "r1", "找到 1 场", List.of(new DiscoveryEventRef(1L, "周六音乐")),
                List.of(), "openai", "gpt-test", null));
        when(events.findAllById(any())).thenReturn(List.of(event(1L, EventStatus.PUBLISHED)));
        gateway.discoveryChat(new DiscoveryChatRequest(null, "有什么活动"), null, "1.2.3.4");

        // 活动在 TTL 内被取消：缓存里的 id 仍在，但复核会把卡片丢掉。
        when(events.findAllById(any())).thenReturn(List.of(event(1L, EventStatus.CANCELLED)));
        DiscoveryChatResponse response = gateway.discoveryChat(
                new DiscoveryChatRequest(null, "有什么活动"), null, "1.2.3.4");

        assertThat(response.events()).isEmpty();
    }

    // ---- 活动发现 ----

    @Test
    void guestGetsSingleTurnAnswerWithoutConversation() {
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        when(client.discoveryChat(any())).thenReturn(new DiscoveryResult(
                "r1", "找到 1 场", List.of(new DiscoveryEventRef(1L, "周六音乐")),
                List.of(), "openai", "gpt-test", new AiUsage(50, 20)));
        when(events.findAllById(any())).thenReturn(List.of(event(1L, EventStatus.PUBLISHED)));

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
                null, null, null, null, null, null, null, null, null, null, null, null)))
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
        // 99 号不在返回结果里：编造的 id 查不到，等同于被丢弃。
        when(events.findAllById(any())).thenReturn(List.of(
                event(1L, EventStatus.PUBLISHED),
                event(2L, EventStatus.CANCELLED),
                event(3L, EventStatus.FINISHED)));

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
        when(events.findAllById(any())).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            List<Event> found = new java.util.ArrayList<>();
            ids.forEach(id -> found.add(event(id, EventStatus.PUBLISHED)));
            return found;
        });

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
