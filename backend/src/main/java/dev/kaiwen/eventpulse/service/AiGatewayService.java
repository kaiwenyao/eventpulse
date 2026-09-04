package dev.kaiwen.eventpulse.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.dto.AiDtos.AiUser;
import dev.kaiwen.eventpulse.dto.AiDtos.ConversationDetail;
import dev.kaiwen.eventpulse.dto.AiDtos.ConversationMessage;
import dev.kaiwen.eventpulse.dto.AiDtos.ConversationSummary;
import dev.kaiwen.eventpulse.dto.AiDtos.CopySuggestion;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryChatRequest;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryChatResponse;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryEventMention;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryEventRef;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryResult;
import dev.kaiwen.eventpulse.dto.AiDtos.HistoryMessage;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventPayload;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventRequest;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventResponse;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventResult;
import dev.kaiwen.eventpulse.dto.EventDtos.EventVo;
import dev.kaiwen.eventpulse.entity.AiConversation;
import dev.kaiwen.eventpulse.entity.AiMessage;
import dev.kaiwen.eventpulse.entity.AiRequestLog;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.AiConversationRepository;
import dev.kaiwen.eventpulse.repository.AiMessageRepository;
import dev.kaiwen.eventpulse.repository.AiRequestLogRepository;
import dev.kaiwen.eventpulse.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * AI 网关：鉴权、限流、调用 Python AI 服务、校验返回结果并持久化会话。
 *
 * 浏览器只与 Spring Boot 交互；LLM API Key 只存在于 Python 服务的 Secret 中。
 * 活动发现返回的活动 ID 在这里再做一次可见性复核：只有 PUBLISHED / ONGOING
 * 的活动会交给前端，Agent 编造的或刚下架的活动 ID 会被静默丢弃。
 */
@Service
public class AiGatewayService {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayService.class);

    static final String FEATURE_IMPROVE = "improve-event";
    static final String FEATURE_DISCOVERY = "discovery";

    private static final int MAX_ANSWER_CHARS = 2000;
    private static final int MAX_REASON_CHARS = 200;
    private static final int MAX_FOLLOW_UPS = 3;
    private static final int MAX_WARNINGS = 6;
    private static final int MAX_WARNING_CHARS = 300;
    private static final int CONVERSATION_PREVIEW_CHARS = 80;

    private final AppProperties properties;
    private final AiServiceClient client;
    private final AiRateLimiter rateLimiter;
    private final JwtService jwtService;
    private final EventRepository events;
    private final EventService eventService;
    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final AiRequestLogRepository requestLogs;
    private final AiTokenBudget budget;
    private final AiResponseCache cache;
    private final MeterRegistry meterRegistry;

    public AiGatewayService(AppProperties properties, AiServiceClient client, AiRateLimiter rateLimiter,
            JwtService jwtService, EventRepository events, EventService eventService,
            AiConversationRepository conversations, AiMessageRepository messages,
            AiRequestLogRepository requestLogs, AiTokenBudget budget, AiResponseCache cache,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.client = client;
        this.rateLimiter = rateLimiter;
        this.jwtService = jwtService;
        this.events = events;
        this.eventService = eventService;
        this.conversations = conversations;
        this.messages = messages;
        this.requestLogs = requestLogs;
        this.budget = budget;
        this.cache = cache;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 主办方文案助手：普通 LLM 调用 + 结构化输出，不用 Agent。
     * 只返回建议，不保存任何业务数据；保存仍走普通活动接口。
     */
    public ImproveEventResponse improveEvent(ImproveEventRequest request) {
        requireEnabled();
        Long userId = BaseContext.getUserId();
        if (!"ORGANISER".equals(BaseContext.getRole())) {
            throw BusinessException.forbidden("Only organisers can use the copy assistant");
        }
        if (request.eventId() != null) {
            // 编辑已有活动时校验主办方权限：只能完善自己的活动。
            events.findByIdAndOrganiserId(request.eventId(), userId)
                    .orElseThrow(() -> BusinessException.forbidden("You can only improve your own events"));
        }
        // 每次调用都是真实的 LLM 成本：主办方也要按用户限流，防止脚本刷调用。
        if (!rateLimiter.tryAcquire("user:" + userId, rateLimiter.userLimit())) {
            throw tooManyRequests();
        }
        // requestId 每次都是新的，所以它绝不能进缓存 key（否则命中率恒为 0），
        // 而命中缓存时返回的 requestId 也必须换成这一次的。
        String requestId = UUID.randomUUID().toString();
        String cacheKey = improveCacheKey(request);
        if (cacheKey != null && !Boolean.TRUE.equals(request.refresh())) {
            ImproveEventResult cached = cache.get(cacheKey, ImproveEventResult.class).orElse(null);
            if (cached != null && cached.suggestion() != null) {
                countCache(FEATURE_IMPROVE, "hit");
                return toImproveResponse(requestId, cached);
            }
        }
        if (cacheKey != null) {
            countCache(FEATURE_IMPROVE, "miss");
        }

        // 预算检查放在缓存之后：命中缓存没有真的调模型，既不该扣预算，也不该被预算挡住。
        if (!budget.hasBudget(userId)) {
            throw budgetExhausted(FEATURE_IMPROVE);
        }

        ImproveEventPayload payload = new ImproveEventPayload(
                requestId,
                truncate(request.title(), 200),
                truncate(request.summary(), 300),
                truncate(request.description(), 5000),
                truncate(request.category(), 50),
                truncate(request.city(), 50),
                truncate(request.venueName(), 200),
                truncate(request.audience(), 300),
                truncate(request.tone(), 200),
                request.startsAt() == null ? null : request.startsAt().toString(),
                request.priceCents());
        long start = System.currentTimeMillis();
        try {
            ImproveEventResult result = client.improveEvent(payload);
            recordSuccess(requestId, userId, FEATURE_IMPROVE, result.provider(), result.model(),
                    start, result.usage());
            if (result.suggestion() == null) {
                throw new AiUnavailableException(AiServiceClient.UNAVAILABLE);
            }
            if (cacheKey != null) {
                cache.put(cacheKey, result, Duration.ofSeconds(properties.getAi().getCacheImproveTtlSeconds()));
            }
            countMetric("ai.requests", FEATURE_IMPROVE, "success");
            return toImproveResponse(requestId, result);
        }
        catch (AiUnavailableException e) {
            recordFailure(requestId, userId, FEATURE_IMPROVE, start, "upstream_unavailable");
            throw e;
        }
    }

    private ImproveEventResponse toImproveResponse(String requestId, ImproveEventResult result) {
        return new ImproveEventResponse(
                requestId,
                new CopySuggestion(
                        truncate(result.suggestion().title(), 200),
                        truncate(result.suggestion().summary(), 300),
                        truncate(result.suggestion().description(), 5000),
                        truncate(result.suggestion().attendanceNotes(), 1000),
                        sanitizeWarnings(result.suggestion().warnings())),
                sanitizeWarnings(result.warnings()));
    }

    /**
     * 文案建议只由提示词里的这些字段决定，所以 key 也只由它们决定：不含 requestId
     * （每次都变）、不含 refresh（是控制位不是输入）、也不含 eventId（根本没进提示词，
     * 带上只会白白降低命中率）。
     */
    private String improveCacheKey(ImproveEventRequest request) {
        if (!cacheUsable()) {
            return null;
        }
        return cache.key("improve",
                request.title(), request.summary(), request.description(),
                request.category(), request.city(), request.venueName(),
                request.audience(), request.tone(), request.startsAt(), request.priceCents());
    }

    private boolean cacheUsable() {
        return properties.getAi().isCacheEnabled() && cache.isAvailable();
    }

    /**
     * 自然语言找活动：LangChain Agent 在 Python 服务里通过受控工具查询真实活动。
     * 登录用户的会话保存在 PostgreSQL；游客是不持久化的单轮请求。
     */
    public DiscoveryChatResponse discoveryChat(DiscoveryChatRequest request, String authorization, String clientIp) {
        requireEnabled();
        AiUser user = resolveUser(authorization);
        Long userId = user == null ? null : user.userId();
        if (userId != null) {
            if (!rateLimiter.tryAcquire("user:" + userId, rateLimiter.userLimit())) {
                throw tooManyRequests();
            }
        }
        else if (!rateLimiter.tryAcquire("ip:" + clientIp, rateLimiter.ipLimit())) {
            throw tooManyRequests();
        }
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            throw new BusinessException("Message is required");
        }
        if (message.length() > properties.getAi().getMaxMessageChars()) {
            throw new BusinessException("Message is too long");
        }

        AiConversation conversation = null;
        List<HistoryMessage> history = List.of();
        if (userId != null) {
            conversation = openConversation(userId, request.conversationId());
            history = recentHistory(conversation.getId());
        }

        String requestId = UUID.randomUUID().toString();
        String contextToken = user == null ? null : jwtService.createContextToken(
                user.userId(), user.role(), requestId, properties.getAi().getContextTokenTtlSeconds());

        // 可共享缓存的判据是「没有用户上下文」而不是「没有登录用户」：contextToken 为空时
        // Python 侧的 has_user_context 为假，压根不会注册任何个人化工具，答案与身份无关。
        // 再加上「没有历史」，这一问就是可以复用的独立提问 —— 首页那几个固定引导语正是如此。
        String cacheKey = contextToken == null && history.isEmpty() ? discoveryCacheKey(message) : null;
        if (cacheKey != null) {
            DiscoveryResult cached = cache.get(cacheKey, DiscoveryResult.class).orElse(null);
            if (cached != null) {
                countCache(FEATURE_DISCOVERY, "hit");
                // 卡片仍会重新复核可见性；answer 正文是最长 TTL 之前的措辞，不再复核。
                return toDiscoveryResponse(requestId, null, cached);
            }
            countCache(FEATURE_DISCOVERY, "miss");
        }

        if (!budget.hasBudget(userId)) {
            throw budgetExhausted(FEATURE_DISCOVERY);
        }

        DiscoveryPayload payload = new DiscoveryPayload(
                requestId,
                message,
                history,
                Instant.now().toString(),
                properties.getAi().getTimeZone(),
                user,
                contextToken);

        long start = System.currentTimeMillis();
        try {
            DiscoveryResult result = client.discoveryChat(payload);
            recordSuccess(requestId, userId, FEATURE_DISCOVERY, result.provider(), result.model(),
                    start, result.usage());
            if (userId != null) {
                appendMessages(conversation, message, result.answer());
            }
            if (cacheKey != null) {
                cache.put(cacheKey, result, Duration.ofSeconds(properties.getAi().getCacheDiscoveryTtlSeconds()));
            }
            countMetric("ai.requests", FEATURE_DISCOVERY, "success");
            return toDiscoveryResponse(requestId, conversation, result);
        }
        catch (AiUnavailableException e) {
            recordFailure(requestId, userId, FEATURE_DISCOVERY, start, "upstream_unavailable");
            throw e;
        }
    }

    private DiscoveryChatResponse toDiscoveryResponse(String requestId, AiConversation conversation,
            DiscoveryResult result) {
        return new DiscoveryChatResponse(
                requestId,
                conversation == null ? null : String.valueOf(conversation.getId()),
                truncate(result.answer() == null || result.answer().isBlank()
                        ? "I could not produce an answer this time, please try again." : result.answer(),
                        MAX_ANSWER_CHARS),
                verifyEvents(result.events()),
                sanitizeFollowUps(result.followUpQuestions()));
    }

    /**
     * 只按用户这一句话建 key。刻意不含时区（它来自 properties 的服务端常量，放进去
     * 只会制造虚假的安全感），也不含 nowIso —— 相对日期的漂移由 120s 的 TTL 兜住。
     */
    private String discoveryCacheKey(String message) {
        if (!cacheUsable()) {
            return null;
        }
        return cache.key("discovery", message.toLowerCase(java.util.Locale.ROOT));
    }

    // ---- 会话生命周期：列表 / 恢复 / 删除 ----

    /**
     * 当前用户的发现助手会话列表。这三个接口都不在 JwtInterceptor 的公开白名单里，
     * 所以走到这里一定有登录态；归属仍然逐条复核，不靠路径推断。
     */
    public List<ConversationSummary> listConversations() {
        Long userId = requireSignedIn();
        return conversations.findByUserIdAndKindOrderByUpdatedAtDesc(userId, FEATURE_DISCOVERY,
                        PageRequest.of(0, properties.getAi().getConversationListLimit()))
                .getContent().stream()
                .map(conversation -> new ConversationSummary(
                        String.valueOf(conversation.getId()),
                        previewOf(conversation.getId()),
                        conversation.getUpdatedAt()))
                .toList();
    }

    /**
     * 恢复一段会话。只还原文字：ai_messages 里本来就只有 role/content，活动卡片与
     * 追问按钮无法重放 —— 前端要如实说明，不能假装还原了全部内容。
     */
    public ConversationDetail getConversation(String conversationId) {
        Long userId = requireSignedIn();
        AiConversation conversation = requireOwnedConversation(conversationId, userId);
        List<ConversationMessage> content = messages.findByConversationIdOrderByIdAsc(conversation.getId(),
                        PageRequest.of(0, properties.getAi().getConversationMessageLimit()))
                .getContent().stream()
                .map(m -> new ConversationMessage(m.getRole(), m.getContent(), m.getCreatedAt()))
                .toList();
        return new ConversationDetail(String.valueOf(conversation.getId()), content);
    }

    /** 用户主动删除。消息有外键指向会话，必须先删消息。 */
    @Transactional
    public void deleteConversation(String conversationId) {
        Long userId = requireSignedIn();
        AiConversation conversation = requireOwnedConversation(conversationId, userId);
        messages.deleteByConversationIdIn(List.of(conversation.getId()));
        conversations.deleteByIdIn(List.of(conversation.getId()));
    }

    private static Long requireSignedIn() {
        Long userId = BaseContext.getUserId();
        if (userId == null) {
            throw BusinessException.forbidden("Please sign in to manage AI conversations");
        }
        return userId;
    }

    private AiConversation requireOwnedConversation(String conversationId, Long userId) {
        AiConversation conversation = conversations.findById(parseConversationId(conversationId))
                .orElseThrow(() -> BusinessException.notFound("Conversation not found"));
        if (!userId.equals(conversation.getUserId())) {
            throw BusinessException.forbidden("You can only access your own conversations");
        }
        return conversation;
    }

    private static Long parseConversationId(String conversationId) {
        try {
            return Long.valueOf(conversationId);
        }
        catch (NumberFormatException e) {
            throw new BusinessException("Invalid conversation id");
        }
    }

    /** 列表预览取最后一条消息；没有消息（刚建就没发成功）时给空串而不是 null。 */
    private String previewOf(Long conversationId) {
        return messages.findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, 1))
                .getContent().stream()
                .findFirst()
                .map(m -> truncate(m.getContent(), CONVERSATION_PREVIEW_CHARS))
                .orElse("");
    }

    private void requireEnabled() {
        if (!properties.getAi().isEnabled()) {
            throw new AiUnavailableException("AI assistant is not enabled on this deployment");
        }
    }

    private static BusinessException tooManyRequests() {
        return new BusinessException("Too many AI requests, please try again in a minute",
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
    }

    /** 与限流区分开：这是「今天的额度用完了」，等一分钟没有用。 */
    private BusinessException budgetExhausted(String feature) {
        countMetric("ai.budget.rejected", feature, "rejected");
        return new BusinessException("The daily AI budget has been used up, please try again tomorrow",
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * discovery/chat 是公开路径（JwtInterceptor 对其放行），这里自行解析
     * 可选的 Bearer token。无效 token 一律按游客处理，不变成 401。
     */
    private AiUser resolveUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            // parseLoginToken 同样拒绝带 purpose 的服务间 token：泄漏的上下文
            // token 当 Bearer 用时按游客处理，而不是冒充登录用户。
            var claims = jwtService.parseLoginToken(authorization.substring(7));
            Long userId = claims.get("userId", Number.class).longValue();
            String role = claims.get("role", String.class);
            return new AiUser(userId, role == null ? "USER" : role);
        }
        catch (Exception e) {
            return null;
        }
    }

    private AiConversation openConversation(Long userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            AiConversation conversation = new AiConversation();
            conversation.setUserId(userId);
            conversation.setKind(FEATURE_DISCOVERY);
            return conversations.save(conversation);
        }
        Long id;
        try {
            id = Long.valueOf(conversationId);
        }
        catch (NumberFormatException e) {
            throw new BusinessException("Invalid conversation id");
        }
        AiConversation conversation = conversations.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Conversation not found"));
        if (!userId.equals(conversation.getUserId())) {
            throw BusinessException.forbidden("You can only continue your own conversations");
        }
        return conversation;
    }

    private List<HistoryMessage> recentHistory(Long conversationId) {
        return messages.findByConversationIdOrderByIdDesc(conversationId,
                        PageRequest.of(0, properties.getAi().getHistoryLimit())).getContent()
                .stream()
                .map(m -> new HistoryMessage(m.getRole(), m.getContent()))
                .toList()
                .reversed();
    }

    private void appendMessages(AiConversation conversation, String question, String answer) {
        // 会话行随之更新 updated_at：ix_ai_conversations_user (user_id, updated_at DESC)
        // 才能真实反映「最近会话」。
        conversation.setUpdatedAt(Instant.now());
        conversations.save(conversation);
        saveMessage(conversation.getId(), AiMessage.ROLE_USER, question,
                properties.getAi().getMaxMessageChars());
        saveMessage(conversation.getId(), AiMessage.ROLE_ASSISTANT,
                answer == null ? "" : answer, MAX_ANSWER_CHARS);
    }

    private void saveMessage(Long conversationId, String role, String content, int maxChars) {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        // 助手回答按响应同样的上限（2000）入库：历史与用户实际看到的保持一致。
        message.setContent(truncate(content, maxChars));
        messages.save(message);
    }

    /**
     * AI 输出按不可信数据处理：逐个重新读取活动，丢弃编造、下架、取消或已
     * 结束的 ID，只保留当前仍公开可看的活动，并重新核对关键字段。
     */
    private List<DiscoveryEventMention> verifyEvents(List<DiscoveryEventRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        Map<Long, DiscoveryEventMention> kept = new LinkedHashMap<>();
        for (DiscoveryEventRef ref : refs) {
            if (kept.size() >= properties.getAi().getMaxEvents()) {
                break;
            }
            if (ref == null || ref.eventId() == null) {
                continue;
            }
            Event event = events.findById(ref.eventId()).orElse(null);
            if (event == null || !EventStatus.PUBLIC_LIST.contains(event.getStatus())) {
                continue;
            }
            if (EventStatus.FINISHED.equals(event.getStatus())) {
                continue;
            }
            EventVo vo = eventService.toVo(event);
            if (!vo.id().equals(event.getId()) || vo.title() == null) {
                continue;
            }
            kept.put(event.getId(), new DiscoveryEventMention(vo, truncate(ref.reason(), MAX_REASON_CHARS)));
        }
        return new ArrayList<>(kept.values());
    }

    private List<String> sanitizeWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return List.of();
        }
        return warnings.stream()
                .filter(w -> w != null && !w.isBlank())
                .limit(MAX_WARNINGS)
                .map(w -> truncate(w, MAX_WARNING_CHARS))
                .toList();
    }

    private List<String> sanitizeFollowUps(List<String> followUps) {
        if (followUps == null || followUps.isEmpty()) {
            return List.of();
        }
        return followUps.stream()
                .filter(q -> q != null && !q.isBlank())
                .limit(MAX_FOLLOW_UPS)
                .map(q -> truncate(q, MAX_REASON_CHARS))
                .toList();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void recordSuccess(String requestId, Long userId, String feature, String provider, String model,
            long start, dev.kaiwen.eventpulse.dto.AiDtos.AiUsage usage) {
        int latencyMs = (int) (System.currentTimeMillis() - start);
        saveLog(requestId, userId, feature, provider, model, "success", null, latencyMs, usage);
        recordLatency(feature, latencyMs);
        countTokens(feature, usage);
        if (usage != null) {
            budget.record(userId, usage.inputTokens(), usage.outputTokens());
        }
    }

    private void recordFailure(String requestId, Long userId, String feature, long start, String errorCode) {
        int latencyMs = (int) (System.currentTimeMillis() - start);
        saveLog(requestId, userId, feature, "unknown", "unknown", "failure", errorCode, latencyMs, null);
        recordLatency(feature, latencyMs);
        // 失败的那一轮同样烧掉了 token（跑满工具循环后才超时的最贵），但上游 502 不带
        // usage。按固定惩罚值记账，否则能稳定触发失败的用户就等于没有预算。
        budget.record(userId, properties.getAi().getFailureTokenPenalty(), 0);
        countMetric("ai.requests", feature, "failure");
        countMetric("ai.failures", feature, "failure");
    }

    private void saveLog(String requestId, Long userId, String feature, String provider, String model,
            String status, String errorCode, Integer latencyMs, dev.kaiwen.eventpulse.dto.AiDtos.AiUsage usage) {
        try {
            AiRequestLog entry = new AiRequestLog();
            entry.setRequestId(requestId);
            entry.setUserId(userId);
            entry.setFeature(feature);
            entry.setProvider(provider == null ? "unknown" : truncate(provider, 40));
            entry.setModelName(model == null ? "unknown" : truncate(model, 100));
            entry.setStatus(status);
            entry.setErrorCode(errorCode);
            entry.setLatencyMs(latencyMs);
            if (usage != null) {
                entry.setInputTokens(usage.inputTokens());
                entry.setOutputTokens(usage.outputTokens());
            }
            requestLogs.save(entry);
        }
        catch (Exception e) {
            // 记录失败不影响主流程；日志里不打提示词、回复或任何凭证。
            log.warn("failed to persist ai request log for feature {}", feature);
        }
    }

    private void countMetric(String name, String feature, String status) {
        meterRegistry.counter(name, "feature", feature, "status", status).increment();
    }

    /**
     * 缓存命中/未命中单独一个指标，刻意不塞进 ai.requests 的 status 标签：那会改变
     * 现有面板里 failure/(success+failure) 的分母。
     */
    private void countCache(String feature, String result) {
        meterRegistry.counter("ai.cache", "feature", feature, "result", result).increment();
    }

    private void recordLatency(String feature, int latencyMs) {
        meterRegistry.timer("ai.latency", "feature", feature).record(latencyMs, TimeUnit.MILLISECONDS);
    }

    private void countTokens(String feature, dev.kaiwen.eventpulse.dto.AiDtos.AiUsage usage) {
        if (usage == null) {
            return;
        }
        if (usage.inputTokens() != null && usage.inputTokens() > 0) {
            meterRegistry.counter("ai.tokens", "feature", feature, "kind", "input")
                    .increment(usage.inputTokens());
        }
        if (usage.outputTokens() != null && usage.outputTokens() > 0) {
            meterRegistry.counter("ai.tokens", "feature", feature, "kind", "output")
                    .increment(usage.outputTokens());
        }
    }

}
