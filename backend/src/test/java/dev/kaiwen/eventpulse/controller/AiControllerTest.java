package dev.kaiwen.eventpulse.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.AiDtos.CopySuggestion;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryChatRequest;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryChatResponse;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventRequest;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventResponse;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.exception.GlobalExceptionHandler;
import dev.kaiwen.eventpulse.service.AiGatewayService;
import dev.kaiwen.eventpulse.service.AiUnavailableException;
import jakarta.servlet.http.HttpServletRequest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiControllerTest {

    @Mock AiGatewayService gateway;
    @Mock HttpServletRequest httpRequest;

    @AfterEach
    void clear() {
        BaseContext.clear();
    }

    @Test
    void improveEventRequiresOrganiserRole() {
        BaseContext.setUserId(2L);
        BaseContext.setRole("USER");
        AiController controller = new AiController(gateway);
        assertThatThrownBy(() -> controller.improveEvent(new ImproveEventRequest(
                null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("organisers");
    }

    @Test
    void improveEventDelegatesToGateway() {
        BaseContext.setUserId(9L);
        BaseContext.setRole("ORGANISER");
        when(gateway.improveEvent(any())).thenReturn(new ImproveEventResponse(
                "r1", new CopySuggestion("t", "s", "d", "n", List.of()), List.of()));
        AiController controller = new AiController(gateway);
        Result<ImproveEventResponse> result = controller.improveEvent(new ImproveEventRequest(
                null, "t", null, null, null, null, null, null, null, null, null, null));
        assertThat(result.getData().requestId()).isEqualTo("r1");
        assertThat(result.getCode()).isEqualTo(1);
    }

    @Test
    void discoveryChatPassesAuthorizationAndClientIp() {
        // XFF 最后一项由本层可信代理（nginx / ingress）写入，客户端伪造的前置
        // 条目不参与限流；这里取的是 10.0.0.2。
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2");
        when(gateway.discoveryChat(any(), eq("Bearer tok"), eq("10.0.0.2")))
                .thenReturn(new DiscoveryChatResponse("r", "1", "ok", List.of(), List.of()));
        AiController controller = new AiController(gateway);
        var result = controller.discoveryChat(new DiscoveryChatRequest("1", "问题"), "Bearer tok", httpRequest);
        assertThat(result.getData().answer()).isEqualTo("ok");
    }

    @Test
    void discoveryChatWithoutForwardedHeaderUsesRemoteAddr() {
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(gateway.discoveryChat(any(), eq(null), eq("127.0.0.1")))
                .thenReturn(new DiscoveryChatResponse("r", null, "ok", List.of(), List.of()));
        AiController controller = new AiController(gateway);
        assertThat(controller.discoveryChat(new DiscoveryChatRequest(null, "问题"), null, httpRequest)
                .getData().answer()).isEqualTo("ok");
    }

    @Test
    void unavailableExceptionKeepsHttpStatus503() throws Exception {
        BaseContext.setUserId(9L);
        BaseContext.setRole("ORGANISER");
        when(gateway.improveEvent(any())).thenThrow(
                new AiUnavailableException("AI assistant is not enabled on this deployment"));
        // 走真实的 MockMvc + GlobalExceptionHandler：断言的是响应状态码，
        // 而不是框架常量。AI 降级必须是 503，不能混进 500 告警里。
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiController(gateway))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(post("/api/ai/organiser/improve-event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("AI assistant is not enabled on this deployment"));
    }
}