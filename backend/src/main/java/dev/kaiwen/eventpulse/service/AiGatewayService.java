package dev.kaiwen.eventpulse.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.dto.AiDtos.AiUsage;
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
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryStreamEvent;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryStreamEventRef;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryStreamResult;
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
    /** 单次发现回答的 SSE 连接超时：30 分钟，足够最慢的 Agent 往返（沿用通知频道口径）。 */
    private static final long DISCOVERY_STREAM_TIMEOUT_MS = 30L * 60 * 1000;
    /** 后台转发 Python 流的线程：不占 Tomcat 线程。
     * 每轮问答用一条虚拟线程阻塞读 Python 流直到结束；虚拟线程池化成本低，
     * 规模由 AI 限流（每用户/每 IP 每分钟）兜底。 */
    private static final ExecutorService DISCOVERY_STREAM_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
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
        DiscoverySession session = prepareDiscovery(request, authorization, clientIp);
        try {
            DiscoveryResult result = client.discoveryChat(session.payload());
            recordSuccess(session.requestId(), session.userId(), FEATURE_DISCOVERY, result.provider(), result.model(),
                    session.start(), result.usage());
            if (session.userId() != null) {
                appendMessages(session.conversation(), session.message(), result.answer());
            }
            countMetric("ai.requests", FEATURE_DISCOVERY, "success");
            return toDiscoveryResponse(session.requestId(), session.conversation(), result);
        }
        catch (AiUnavailableException e) {
            recordFailure(session.requestId(), session.userId(), FEATURE_DISCOVERY, session.start(), "upstream_unavailable");
            throw e;
        }
    }

    /**
     * 发现助手的流式版本：先把限流 / 会话 / 历史 / 偏好全部在请求线程上准备好
     * （异步处理一开始 BaseContext 就被清掉，身份必须在返回 emitter 之前捕获），
     * 然后建一条 SSE 连接，在后台线程把 Python 的 delta 逐字转发、收尾时做
     * verifyEvents + 落库会话 + 记日志。
     */
    public SseEmitter openDiscoveryStream(DiscoveryChatRequest request, String authorization, String clientIp) {
        DiscoverySession session = prepareDiscovery(request, authorization, clientIp);
        SseEmitter emitter = new SseEmitter(DISCOVERY_STREAM_TIMEOUT_MS);
        // 终态记账只做一次：timeout 回调（容器线程）与 relay 线程（收尾）都可能
        // 到达终态，用 finished 保证不会重复记 failure / 重复 countMetric。
        AtomicBoolean finished = new AtomicBoolean(false);
        // 每轮回答是单次、非重连的流：客户端断开即整体放弃，不留半截。
        Runnable relay = () -> relayDiscoveryStream(session, emitter, finished);
        emitter.onCompletion(() -> log.debug("discovery stream completed request_id={}", session.requestId()));
        emitter.onTimeout(() -> {
            log.warn("discovery stream timed out request_id={}", session.requestId());
            if (finished.compareAndSet(false, true)) {
                recordFailure(session.requestId(), session.userId(), FEATURE_DISCOVERY, session.start(), "stream_timeout");
            }
            emitter.complete();
        });
        DISCOVERY_STREAM_EXECUTOR.execute(relay);
        return emitter;
    }

    /** 后台线程：把 Python 流逐条转发；成功时落库会话 + 记日志，出错时发 error 事件。 */
    private void relayDiscoveryStream(DiscoverySession session, SseEmitter emitter, AtomicBoolean finished) {
        StreamAccumulator accumulator = new StreamAccumulator();
        try {
            client.streamDiscoveryChat(session.payload(),
                    event -> relayStreamEvent(session, emitter, accumulator, event));
            // Python 关流但既没给 result 也没发过 error 帧：补一条明确的 error 帧，
            // 不让浏览器只看到连接静默关闭。
            if (!accumulator.gotResult && !accumulator.failed) {
                sendErrorBestEffort(emitter, AiServiceClient.UNAVAILABLE);
            }
        }
        catch (AiServiceClient.StreamRelayAborted aborted) {
            if (finished.compareAndSet(false, true)) {
                recordFailure(session.requestId(), session.userId(), FEATURE_DISCOVERY, session.start(), "client_disconnected");
            }
            return;
        }
        catch (AiUnavailableException e) {
            // 上游故障，或转发回调里的 verifyEvents / 落库失败（AiServiceClient
            // 会把回调抛出的其它异常也转成 AiUnavailableException）。
            if (finished.compareAndSet(false, true)) {
                recordFailure(session.requestId(), session.userId(), FEATURE_DISCOVERY, session.start(), "upstream_unavailable");
            }
            sendErrorBestEffort(emitter, e.getMessage());
            return;
        }
        catch (RuntimeException e) {
            // 帧解析 / 其它意外：不能把内部细节带给浏览器，统一按不可用降级。
            log.warn("discovery stream relay failed request_id={}", session.requestId(), e);
            if (finished.compareAndSet(false, true)) {
                recordFailure(session.requestId(), session.userId(), FEATURE_DISCOVERY, session.start(), "upstream_unavailable");
            }
            sendErrorBestEffort(emitter, AiServiceClient.UNAVAILABLE);
            return;
        }
        finally {
            emitter.complete();
        }
        if (!finished.compareAndSet(false, true)) {
            // 已被 timeout 回调收尾（30 分钟挂起 + 迟到完成）：不重复记账。
            return;
        }
        if (accumulator.failed || !accumulator.gotResult) {
            // Python 明确发过 error 帧，或流结束了却没给 result：不能留半截冒充完整。
            recordFailure(session.requestId(), session.userId(), FEATURE_DISCOVERY, session.start(),
                    accumulator.failed ? "upstream_error" : "no_result");
            return;
        }
        // 成功：日志与计量在流结束后做（Python 的 usage 此时才有）；会话落库
        // 已提前到 done 帧之前（见 relayStreamEvent），用户追问时历史才完整。
        recordSuccess(session.requestId(), session.userId(), FEATURE_DISCOVERY, accumulator.provider,
                accumulator.model, session.start(), accumulator.usage);
        countMetric("ai.requests", FEATURE_DISCOVERY, "success");
    }

    private void relayStreamEvent(DiscoverySession session, SseEmitter emitter,
            StreamAccumulator accumulator, DiscoveryStreamEvent event) {
        if ("delta".equals(event.type())) {
            sendOrAbort(emitter, SseEmitter.event().name("delta")
                    .data(Map.of("text", event.text()), MediaType.APPLICATION_JSON));
        }
        else if ("result".equals(event.type())) {
            DiscoveryStreamResult result = event.result();
            accumulator.gotResult = true;
            accumulator.provider = result.provider();
            accumulator.model = result.model();
            accumulator.usage = result.usage();
            accumulator.answer = result.answer();
            // 活动卡片只在流结束时一次性发：verifyEvents 需要完整列表。
            // verifyEvents / 落库抛出的异常绝不能包成 StreamRelayAborted——只有
            // send 失败才是浏览器断开，DB 故障要走 upstream_unavailable 降级。
            List<DiscoveryEventMention> verified = verifyEvents(toRefs(result.events()));
            // 落库必须先于 done：done 一到前端就会解锁追问按钮，若此刻历史里
            // 还没有本轮问答，紧跟着的追问会丢掉刚发生的这一轮上下文。
            if (session.userId() != null) {
                appendMessages(session.conversation(), session.message(), accumulator.answer);
            }
            sendOrAbort(emitter, SseEmitter.event().name("done")
                    .data(toStreamDone(session, result.answer(), verified,
                            result.followUpQuestions()), MediaType.APPLICATION_JSON));
        }
        else if ("error".equals(event.type())) {
            accumulator.failed = true;
            sendOrAbort(emitter, SseEmitter.event().name("error")
                    .data(Map.of("message", event.error()), MediaType.APPLICATION_JSON));
        }
    }

    /** 发一帧；浏览器断开（send 抛 IOException）转成内部信号，别把 IO 异常泄漏成 500。
     * 只有 IOException 才算断线：send 的其它 RuntimeException（如 done 帧 Jackson
     * 序列化失败）是服务端 bug，让它穿出去——AiServiceClient 会把回调异常包成
     * AiUnavailableException，落到 upstream_unavailable 分支并补发 error 帧，
     * 不能在监控里伪装成「用户关页面」。 */
    private static void sendOrAbort(SseEmitter emitter, SseEmitter.SseEventBuilder frame) {
        try {
            emitter.send(frame);
        }
        catch (IOException sendFailure) {
            throw new AiServiceClient.StreamRelayAborted(sendFailure);
        }
    }

    private static void sendErrorBestEffort(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("message", message), MediaType.APPLICATION_JSON));
        }
        catch (Exception ignored) {
            // 浏览器也已断开，错误帧发不出去就算了。
        }
    }

    private static List<DiscoveryEventRef> toRefs(List<DiscoveryStreamEventRef> refs) {
        if (refs == null) {
            return List.of();
        }
        return refs.stream()
                .map(r -> new DiscoveryEventRef(r.eventId(), r.reason()))
                .toList();
    }

    private DiscoveryChatResponse toStreamDone(DiscoverySession session, String answer,
            List<DiscoveryEventMention> verified, List<String> followUps) {
        return new DiscoveryChatResponse(
                session.requestId(),
                session.conversation() == null ? null : String.valueOf(session.conversation().getId()),
                truncate(answer == null || answer.isBlank()
                        ? "I could not produce an answer this time, please try again." : answer,
                        MAX_ANSWER_CHARS),
                verified,
                sanitizeFollowUps(followUps));
    }

    /** 转发过程中的可变状态：lambda 需要写回，不能只靠局部变量。 */
    private static final class StreamAccumulator {
        private boolean gotResult;
        private boolean failed;
        private String provider = "unknown";
        private String model = "unknown";
        private AiUsage usage;
        private String answer = "";
    }

    /** 流式请求与同步请求共用的准备阶段：限流、消息校验、会话 / 历史 / 偏好、载荷。 */
    private DiscoverySession prepareDiscovery(DiscoveryChatRequest request, String authorization, String clientIp) {
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
                normalizeLocale(request.locale()),
                user,
                contextToken,
                preferencesOf(userId));
        return new DiscoverySession(requestId, userId, conversation, message, payload,
                System.currentTimeMillis());
    }

    /**
     * 界面语言只接受白名单，其余一律 null。
     *
     * 这个值会拼进 Python 侧的系统提示词，属于外部输入：放行自由文本等于开了
     * 一个注入口子。识别不出来时交给提示词自己的「默认英文」兜底。
     */
    private String normalizeLocale(String locale) {
        if (locale == null) {
            return null;
        }
        String normalized = locale.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("en")) {
            return "en";
        }
        if (normalized.startsWith("zh")) {
            return "zh";
        }
        return null;
    }

    /** 一次发现请求在请求线程上捕获的全部上下文（异步线程不再有 BaseContext）。 */
    private record DiscoverySession(String requestId, Long userId, AiConversation conversation, String message,
            DiscoveryPayload payload, long start) {
    }


    private DiscoveryChatResponse toDiscoveryResponse(String requestId, AiConversation conversation,
            DiscoveryResult result) {
        return new DiscoveryChatResponse(
                requestId,
                conversation == null ? null : String.valueOf(conversation.getId()),
                // 空回答不在这里补写死文案：任何写死的句子都只有一种语言，塞进来
                // 反而会盖掉前端按 UI 语言渲染的 ai.discovery.noAnswer。
                truncate(result.answer() == null ? "" : result.answer(), MAX_ANSWER_CHARS),
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
