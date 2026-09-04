package dev.kaiwen.eventpulse.service;

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
import dev.kaiwen.eventpulse.dto.AiDtos.ToolPreferenceVo;
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
import dev.kaiwen.eventpulse.repository.UserPreferenceRepository;
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
    /**
     * 发给 Python 的偏好字段上限。user_preferences 的列是 VARCHAR(300) 且写入侧不
     * 截断，而 Python 的 DiscoveryPreferences 是 max_length=200：库里合法存在的
     * 201–300 字符城市列表会让整个请求被 Pydantic 判 422，Spring 再转成「AI 暂不
     * 可用」，该用户从此每次提问都失败且无法自愈。
     *
     * 截断放在网关而不是放宽 Python：这里是数据往外带的边界，顺带也挡住了以后
     * 有人加宽 DB 列。提示词层反正也只取前 200 字符。
     */
    private static final int MAX_PREFERENCE_CHARS = 200;

    private final AppProperties properties;
    private final AiServiceClient client;
    private final AiRateLimiter rateLimiter;
    private final JwtService jwtService;
    private final EventRepository events;
    private final EventService eventService;
    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final AiRequestLogRepository requestLogs;
    private final UserPreferenceRepository preferences;
    private final MeterRegistry meterRegistry;

    public AiGatewayService(AppProperties properties, AiServiceClient client, AiRateLimiter rateLimiter,
            JwtService jwtService, EventRepository events, EventService eventService,
            AiConversationRepository conversations, AiMessageRepository messages,
            AiRequestLogRepository requestLogs, UserPreferenceRepository preferences,
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
        this.preferences = preferences;
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
        String requestId = UUID.randomUUID().toString();
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

        DiscoveryPayload payload = new DiscoveryPayload(
                requestId,
                message,
                history,
                Instant.now().toString(),
                properties.getAi().getTimeZone(),
                user,
                contextToken,
                preferencesOf(userId));

        long start = System.currentTimeMillis();
        try {
            DiscoveryResult result = client.discoveryChat(payload);
            recordSuccess(requestId, userId, FEATURE_DISCOVERY, result.provider(), result.model(),
                    start, result.usage());
            if (userId != null) {
                appendMessages(conversation, message, result.answer());
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

    /**
     * 登录用户保存的偏好。没有偏好行时返回 null，不返回一个全空的对象 ——
     * 让 Python 侧「有没有偏好」的判断保持简单。
     */
    private ToolPreferenceVo preferencesOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return preferences.findById(userId)
                .map(pref -> new ToolPreferenceVo(
                        truncate(pref.getCategories(), MAX_PREFERENCE_CHARS),
                        truncate(pref.getCities(), MAX_PREFERENCE_CHARS),
                        pref.getLatitude(), pref.getLongitude(), pref.getRadiusKm()))
                .orElse(null);
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
        int maxEvents = properties.getAi().getMaxEvents();
        // 先按 AI 给的顺序去重并截断，再一次查库。原来是逐个 findById（N+1），
        // 那时是循环里的 break 顺带限制了查询次数；换成批量查之后必须显式截断，
        // 否则上游返回 5000 个 id 就会变成一条巨大的 IN。
        List<Long> ids = new ArrayList<>();
        for (DiscoveryEventRef ref : refs) {
            if (ref == null || ref.eventId() == null || ids.contains(ref.eventId())) {
                continue;
            }
            ids.add(ref.eventId());
            if (ids.size() >= maxEvents) {
                break;
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Event> found = new LinkedHashMap<>();
        for (Event event : events.findAllById(ids)) {
            found.put(event.getId(), event);
        }

        Map<Long, DiscoveryEventMention> kept = new LinkedHashMap<>();
        for (DiscoveryEventRef ref : refs) {
            if (kept.size() >= maxEvents || ref == null || ref.eventId() == null) {
                continue;
            }
            Event event = found.get(ref.eventId());
            if (event == null || !EventStatus.PUBLIC_LIST.contains(event.getStatus())) {
                continue;
            }
            // FINISHED 也在 PUBLIC_LIST 里（已结束的活动仍可浏览），但不该再被推荐。
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
    }

    private void recordFailure(String requestId, Long userId, String feature, long start, String errorCode) {
        int latencyMs = (int) (System.currentTimeMillis() - start);
        saveLog(requestId, userId, feature, "unknown", "unknown", "failure", errorCode, latencyMs, null);
        recordLatency(feature, latencyMs);
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
