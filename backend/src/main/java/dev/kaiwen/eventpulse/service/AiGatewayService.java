package dev.kaiwen.eventpulse.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.dto.AiDtos.AiUser;
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

    private final AppProperties properties;
    private final AiServiceClient client;
    private final AiRateLimiter rateLimiter;
    private final JwtService jwtService;
    private final EventRepository events;
    private final EventService eventService;
    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final AiRequestLogRepository requestLogs;
    private final MeterRegistry meterRegistry;

    public AiGatewayService(AppProperties properties, AiServiceClient client, AiRateLimiter rateLimiter,
            JwtService jwtService, EventRepository events, EventService eventService,
            AiConversationRepository conversations, AiMessageRepository messages,
            AiRequestLogRepository requestLogs, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.client = client;
        this.rateLimiter = rateLimiter;
        this.jwtService = jwtService;
        this.events = events;
        this.eventService = eventService;
        this.conversations = conversations;
        this.messages = messages;
        this.requestLogs = requestLogs;
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
        catch (AiUnavailableException e) {
            recordFailure(requestId, userId, FEATURE_IMPROVE, start, "upstream_unavailable");
            throw e;
        }
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
                contextToken);

        long start = System.currentTimeMillis();
        try {
            DiscoveryResult result = client.discoveryChat(payload);
            recordSuccess(requestId, userId, FEATURE_DISCOVERY, result.provider(), result.model(),
                    start, result.usage());
            List<DiscoveryEventMention> mentions = verifyEvents(result.events());
            if (userId != null) {
                appendMessages(conversation, message, result.answer());
            }
            countMetric("ai.requests", FEATURE_DISCOVERY, "success");
            return new DiscoveryChatResponse(
                    requestId,
                    conversation == null ? null : String.valueOf(conversation.getId()),
                    truncate(result.answer() == null || result.answer().isBlank()
                            ? "I could not produce an answer this time, please try again." : result.answer(),
                            MAX_ANSWER_CHARS),
                    mentions,
                    sanitizeFollowUps(result.followUpQuestions()));
        }
        catch (AiUnavailableException e) {
            recordFailure(requestId, userId, FEATURE_DISCOVERY, start, "upstream_unavailable");
            throw e;
        }
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
            var claims = jwtService.parseToken(authorization.substring(7));
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
        saveMessage(conversation.getId(), AiMessage.ROLE_USER, question);
        saveMessage(conversation.getId(), AiMessage.ROLE_ASSISTANT,
                answer == null ? "" : answer);
    }

    private void saveMessage(Long conversationId, String role, String content) {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(truncate(content, properties.getAi().getMaxMessageChars()));
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
        saveLog(requestId, userId, feature, provider, model, "success", null,
                (int) (System.currentTimeMillis() - start), usage);
    }

    private void recordFailure(String requestId, Long userId, String feature, long start, String errorCode) {
        saveLog(requestId, userId, feature, "unknown", "unknown", "failure", errorCode,
                (int) (System.currentTimeMillis() - start), null);
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

}
