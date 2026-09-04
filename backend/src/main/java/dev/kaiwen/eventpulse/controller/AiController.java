package dev.kaiwen.eventpulse.controller;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.AiDtos.ConversationDetail;
import dev.kaiwen.eventpulse.dto.AiDtos.ConversationSummary;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryChatRequest;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryChatResponse;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventRequest;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventResponse;
import dev.kaiwen.eventpulse.service.AiGatewayService;
import dev.kaiwen.eventpulse.service.EventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * AI 助手的浏览器入口。
 *
 * - /api/ai/organiser/improve-event：JWT + ORGANISER（JwtInterceptor 已保证
 *   登录，这里再校验角色与活动所有权）。
 * - /api/ai/discovery/chat：公开路径（游客可单轮提问），带 Bearer 时由网关
 *   解析用户并加载其 PostgreSQL 会话；限流、超时、降级都在网关里处理。
 * - /api/ai/conversations**：会话列表 / 恢复 / 删除。不在 JwtInterceptor 的公开
 *   白名单里，所以天然要求登录；归属仍由网关逐条复核。
 */
@RestController
@Profile("api")
public class AiController {

    private final AiGatewayService gateway;

    public AiController(AiGatewayService gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/api/ai/organiser/improve-event")
    public Result<ImproveEventResponse> improveEvent(@Valid @RequestBody ImproveEventRequest request) {
        EventService.requireOrganiser();
        return Result.success(gateway.improveEvent(request));
    }

    @PostMapping("/api/ai/discovery/chat")
    public Result<DiscoveryChatResponse> discoveryChat(
            @Valid @RequestBody DiscoveryChatRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest httpRequest) {
        return Result.success(gateway.discoveryChat(request, authorization, clientIp(httpRequest)));
    }

    @GetMapping("/api/ai/conversations")
    public Result<List<ConversationSummary>> conversations() {
        return Result.success(gateway.listConversations());
    }

    @GetMapping("/api/ai/conversations/{id}")
    public Result<ConversationDetail> conversation(@PathVariable String id) {
        return Result.success(gateway.getConversation(id));
    }

    @DeleteMapping("/api/ai/conversations/{id}")
    public Result<Void> deleteConversation(@PathVariable String id) {
        gateway.deleteConversation(id);
        return Result.success(null);
    }

    /** XFF 的最后一项由本层可信代理（nginx / ingress）写入，取它做 IP 级限流；
     *  客户端伪造的前置条目不参与限流。无代理时退回 remoteAddr。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
