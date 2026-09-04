package dev.kaiwen.eventpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
                "r2", "m", List.of(), "2026-09-02T00:00:00Z", "Asia/Shanghai", null, null));

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
                "r2", "m", List.of(), null, null, null, null)))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("temporarily unavailable");
    }
    /** 用一个显式熔断器重建客户端：不能从 properties 读，那个字段在测试构造器里是 null。 */
    private AiServiceClient clientWithBreaker(AiCircuitBreaker breaker) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new AiServiceClient(builder
                .baseUrl("http://ai-service:8090")
                .defaultHeader("Authorization", "Bearer svc-token")
                .build(), breaker);
    }

    private static ImproveEventPayload payload() {
        return new ImproveEventPayload("r1", null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void transportFailuresOpenTheBreakerAndLaterCallsNeverReachTheNetwork() {
        AiServiceClient breakered = clientWithBreaker(new AiCircuitBreaker(2, 30_000));
        server.expect(requestTo("http://ai-service:8090/internal/v1/improve-event"))
                .andRespond(withException(new java.net.SocketTimeoutException("read timeout")));
        server.expect(requestTo("http://ai-service:8090/internal/v1/improve-event"))
                .andRespond(withException(new java.net.SocketTimeoutException("read timeout")));

        assertThatThrownBy(() -> breakered.improveEvent(payload())).isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> breakered.improveEvent(payload())).isInstanceOf(AiUnavailableException.class);
        assertThat(breakered.isCircuitOpen()).isTrue();

        // 熔断打开后第三次调用必须立刻失败，一个字节都不发出去 ——
        // server 只期望了两次请求，多发一次这里就会红。
        assertThatThrownBy(() -> breakered.improveEvent(payload())).isInstanceOf(AiUnavailableException.class);
        server.verify();
    }

    @Test
    void applicationLevel502DoesNotOpenTheBreaker() {
        AiServiceClient breakered = clientWithBreaker(new AiCircuitBreaker(2, 30_000));
        // Python 在 LlmOutputError / AgentExecutionError 时返回 502：模型没吐好，
        // 但进程完全健康。连续几次模型抽风不该熔断掉一个活着的服务。
        for (int i = 0; i < 4; i++) {
            server.expect(requestTo("http://ai-service:8090/internal/v1/improve-event"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        }

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> breakered.improveEvent(payload()))
                    .isInstanceOf(AiUnavailableException.class);
        }

        assertThat(breakered.isCircuitOpen()).isFalse();
        server.verify();
    }

    @Test
    void gatewayUnavailable503IsTreatedAsTheUpstreamBeingDown() {
        AiServiceClient breakered = clientWithBreaker(new AiCircuitBreaker(1, 30_000));
        server.expect(requestTo("http://ai-service:8090/internal/v1/improve-event"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> breakered.improveEvent(payload())).isInstanceOf(AiUnavailableException.class);

        assertThat(breakered.isCircuitOpen()).isTrue();
        server.verify();
    }

    @Test
    void theDefaultTestClientHasNoBreakerSoExistingBehaviourIsUnchanged() {
        assertThat(client.isCircuitOpen()).isFalse();
    }

}
