package dev.kaiwen.eventpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.dto.AiDtos.CopySuggestion;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryEventRef;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventPayload;

class AiServiceClientTest {

    private AiServiceClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getAi().setServiceUrl("http://ai-service:8090");
        properties.getAi().setServiceToken("svc-token");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiServiceClient(builder
                .baseUrl(properties.getAi().getServiceUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getAi().getServiceToken())
                .build());
    }

    @Test
    void improveEventSendsServiceTokenAndParsesCamelCaseResponse() {
        server.expect(requestTo("http://ai-service:8090/internal/v1/improve-event"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess("""
                        {
                          "requestId": "r1",
                          "suggestion": {"title": "T", "summary": "S", "description": "D",
                                         "attendanceNotes": "N", "warnings": ["w1"]},
                          "warnings": ["w1"],
                          "provider": "openai",
                          "model": "gpt-test",
                          "usage": {"inputTokens": 11, "outputTokens": 7}
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.improveEvent(new ImproveEventPayload("r1", "t", null, null, null, null, null,
                null, null, null, null));

        assertThat(result.suggestion()).isEqualTo(new CopySuggestion("T", "S", "D", "N", List.of("w1")));
        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.usage().outputTokens()).isEqualTo(7);
        server.verify();
    }

    @Test
    void discoveryChatParsesEventsAndReasons() {
        server.expect(requestTo("http://ai-service:8090/internal/v1/discovery/chat"))
                .andRespond(withSuccess("""
                        {
                          "requestId": "r2",
                          "answer": "找到两场",
                          "events": [{"eventId": 3, "reason": "周六"}],
                          "followUpQuestions": ["q1"],
                          "provider": "openai",
                          "model": "gpt-test"
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.discoveryChat(new dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload(
                "r2", "m", List.of(), "2026-09-02T00:00:00Z", "Asia/Shanghai", "en", null, null, null));

        assertThat(result.events()).containsExactly(new DiscoveryEventRef(3L, "周六"));
        assertThat(result.followUpQuestions()).containsExactly("q1");
    }

    @Test
    void upstreamErrorOrTimeoutBecomesAiUnavailable() {
        server.expect(requestTo("http://ai-service:8090/internal/v1/improve-event"))
                .andRespond(withServerError());
        server.expect(requestTo("http://ai-service:8090/internal/v1/discovery/chat"))
                .andRespond(withException(new java.net.SocketTimeoutException("read timeout")));

        assertThatThrownBy(() -> client.improveEvent(new ImproveEventPayload("r1", null, null, null, null,
                null, null, null, null, null, null)))
                .isInstanceOf(AiUnavailableException.class);

        assertThatThrownBy(() -> client.discoveryChat(new dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload(
                "r2", "m", List.of(), null, null, null, null, null, null)))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("temporarily unavailable");
    }

    // ---- 流式端点 ----

    private static final String STREAM_BODY = """
            event: delta
            data: {"text":"找到两"}

            event: delta
            data: {"text":"场活动"}

            event: result
            data: {"answer":"找到两场活动","events":[{"eventId":3,"reason":"周六"}],"followUpQuestions":["还要更便宜的吗？"],"provider":"openai","model":"gpt-test","usage":{"inputTokens":11,"outputTokens":7},"toolCalls":1}

            """;

    @Test
    void streamDiscoveryChatForwardsDeltaAndResultFrames() {
        server.expect(requestTo("http://ai-service:8090/internal/v1/discovery/chat/stream"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess(STREAM_BODY, MediaType.TEXT_EVENT_STREAM));

        var received = new java.util.ArrayList<dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryStreamEvent>();
        client.streamDiscoveryChat(new dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload(
                "r2", "m", List.of(), null, null, null, null, null), received::add);

        assertThat(received).hasSize(3);
        assertThat(received.get(0).type()).isEqualTo("delta");
        assertThat(received.get(0).text()).isEqualTo("找到两");
        assertThat(received.get(1).text()).isEqualTo("场活动");
        var result = received.get(2).result();
        assertThat(result.answer()).isEqualTo("找到两场活动");
        assertThat(result.events()).containsExactly(
                new dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryStreamEventRef(3L, "周六"));
        assertThat(result.followUpQuestions()).containsExactly("还要更便宜的吗？");
        assertThat(result.usage().outputTokens()).isEqualTo(7);
        assertThat(result.toolCalls()).isEqualTo(1);
        server.verify();
    }

    @Test
    void streamDiscoveryChatParsesErrorFrame() {
        server.expect(requestTo("http://ai-service:8090/internal/v1/discovery/chat/stream"))
                .andRespond(withSuccess("""
                        event: error
                        data: {"message":"AI could not query events right now, please retry"}

                        """, MediaType.TEXT_EVENT_STREAM));

        var received = new java.util.ArrayList<dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryStreamEvent>();
        client.streamDiscoveryChat(new dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload(
                "r2", "m", List.of(), null, null, null, null, null), received::add);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).type()).isEqualTo("error");
        assertThat(received.get(0).error()).contains("could not query");
        server.verify();
    }

    @Test
    void streamDiscoveryChatNon2xxBecomesAiUnavailable() {
        server.expect(requestTo("http://ai-service:8090/internal/v1/discovery/chat/stream"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.streamDiscoveryChat(
                new dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload(
                        "r2", "m", List.of(), null, null, null, null, null),
                event -> {
                }))
                .isInstanceOf(AiUnavailableException.class);
        server.verify();
    }

    @Test
    void streamDiscoveryChatReadFailureBecomesAiUnavailable() {
        server.expect(requestTo("http://ai-service:8090/internal/v1/discovery/chat/stream"))
                .andRespond(withException(new java.net.SocketTimeoutException("read timeout")));

        assertThatThrownBy(() -> client.streamDiscoveryChat(
                new dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload(
                        "r2", "m", List.of(), null, null, null, null, null),
                event -> {
                }))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("temporarily unavailable");
        server.verify();
    }

    @Test
    void streamDiscoveryChatIgnoresUnknownAndMalformedFrames() {
        server.expect(requestTo("http://ai-service:8090/internal/v1/discovery/chat/stream"))
                .andRespond(withSuccess("""
                        event: ping
                        data: {"text":"keepalive"}

                        event: result
                        data: {broken json

                        event: result
                        data: {"answer":"","events":[],"followUpQuestions":[],"provider":"p","model":"m"}

                        """, MediaType.TEXT_EVENT_STREAM));

        var received = new java.util.ArrayList<dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryStreamEvent>();
        client.streamDiscoveryChat(new dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload(
                "r2", "m", List.of(), null, null, null, null, null), received::add);

        // 未知事件类型与坏 JSON 帧被忽略；合法 result（无 events/usage/toolCalls）照常解析。
        assertThat(received).hasSize(1);
        var result = received.get(0).result();
        assertThat(result.answer()).isEmpty();
        assertThat(result.events()).isEmpty();
        assertThat(result.usage().inputTokens()).isNull();
        assertThat(result.toolCalls()).isNull();
        server.verify();
    }
}
