package dev.kaiwen.eventpulse.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AI 助手的请求 / 返回结构。浏览器只与 Spring Boot 交互；带 Tool 前缀的
 * record 用于 Spring Boot ↔ Python AI 服务之间，以及 Agent 工具调用。
 */
public final class AiDtos {

    private AiDtos() {
    }

    /** 主办方文案助手请求：可来自未保存的表单，也可携带主办方自己的活动 ID。 */
    public record ImproveEventRequest(
            Long eventId,
            @Size(max = 200) String title,
            @Size(max = 300) String summary,
            @Size(max = 5000) String description,
            @Size(max = 50) String category,
            @Size(max = 50) String city,
            @Size(max = 200) String venueName,
            @Size(max = 300) String audience,
            @Size(max = 200) String tone,
            Instant startsAt,
            Integer priceCents) {
    }

    /** 文案建议：固定结构，主办方逐项确认后才能应用到表单。 */
    public record CopySuggestion(
            String title,
            String summary,
            String description,
            String attendanceNotes,
            List<String> warnings) {
    }

    public record ImproveEventResponse(
            String requestId,
            CopySuggestion suggestion,
            List<String> warnings) {
    }

    public record DiscoveryChatRequest(
            String conversationId,
            @NotBlank @Size(max = 1000) String message,
            /** 前端当前的 UI 语言（en / zh），服务端会归一化后再往下传。 */
            @Size(max = 10) String locale) {
    }

    public record DiscoveryChatResponse(
            String requestId,
            String conversationId,
            String answer,
            List<DiscoveryEventMention> events,
            List<String> followUpQuestions) {
    }

    /** 返回给前端的一条活动引用：完整 EventVo 附带 Agent 给出的引用理由。 */
    public record DiscoveryEventMention(dev.kaiwen.eventpulse.dto.EventDtos.EventVo event, String reason) {
    }

    /** 会话列表的一行：预览取最后一条消息的开头，够用户认出是哪段对话即可。 */
    public record ConversationSummary(
            String id,
            String preview,
            Instant updatedAt) {
    }

    /** 恢复会话时回填的内容。服务端只存 role/content，所以活动卡片无法重放。 */
    public record ConversationDetail(
            String id,
            List<ConversationMessage> messages) {
    }

    public record ConversationMessage(
            String role,
            String content,
            Instant createdAt) {
    }

    // ---- Spring Boot ↔ Python AI 服务之间的结构 ----

    public record HistoryMessage(String role, String content) {
    }

    public record AiUser(Long userId, String role) {
    }

    /** Python 服务返回的 token 用量（可获取时才有值）。 */
    public record AiUsage(Integer inputTokens, Integer outputTokens) {
    }

    public record ImproveEventPayload(
            String requestId,
            String title,
            String summary,
            String description,
            String category,
            String city,
            String venueName,
            String audience,
            String tone,
            String startsAtIso,
            Integer priceCents) {
    }

    public record ImproveEventResult(
            String requestId,
            CopySuggestion suggestion,
            List<String> warnings,
            String provider,
            String model,
            AiUsage usage) {
    }

    public record DiscoveryPayload(
            String requestId,
            String message,
            List<HistoryMessage> history,
            String nowIso,
            String timeZone,
            /**
             * 归一化后的界面语言（"en" / "zh"，识别不了就是 null）。
             *
             * 只在用户消息本身判断不出语言时给模型兜底：提示词、工具描述都是
             * 中文，短消息（"berlin"、emoji）很容易被带偏成中文回复。
             */
            String locale,
            AiUser user,
            String userContextToken,
            /**
             * 登录用户主动保存的偏好，由 Spring 直接带过去。
             *
             * 之前要靠模型自己想起来调 get_my_preferences 工具，那是一次
             * 「LLM 决策 + HTTP 往返」；而 Spring 本来就持有 UserPreferenceRepository，
             * 一次进程内查询即可。工具仍然保留，模型需要时可以显式重查。
             */
            ToolPreferenceVo preferences) {
    }

    public record DiscoveryEventRef(Long eventId, String reason) {
    }

    public record DiscoveryResult(
            String requestId,
            String answer,
            List<DiscoveryEventRef> events,
            List<String> followUpQuestions,
            String provider,
            String model,
            AiUsage usage) {
    }

    // ---- Python Agent → Spring Boot /internal/ai-tools/** ----

    public record ToolSearchRequest(
            String q,
            String city,
            String category,
            Instant dateFrom,
            Instant dateTo,
            Integer minPriceCents,
            Integer maxPriceCents,
            Boolean hasRemaining,
            Integer limit) {
    }

    public record ToolNearbyRequest(
            Double lat,
            Double lng,
            Double radiusKm,
            Integer limit) {
    }

    /** 工具返回的精简活动视图：足够 Agent 引用，不含主办方内部数据。 */
    public record ToolEventVo(
            Long id,
            String title,
            String summary,
            String description,
            String category,
            String city,
            String venueName,
            String address,
            Double latitude,
            Double longitude,
            Instant startsAt,
            Instant endsAt,
            int priceCents,
            int remaining,
            String status) {
    }

    public record ToolPreferenceVo(
            String categories,
            String cities,
            Double latitude,
            Double longitude,
            Double radiusKm) {
    }

    public record ToolCategoryCount(String category, long count) {
    }
}
