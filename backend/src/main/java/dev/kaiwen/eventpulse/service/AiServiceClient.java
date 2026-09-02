package dev.kaiwen.eventpulse.service;

import java.time.Duration;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryResult;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventPayload;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventResult;

/**
 * Spring Boot → Python AI 服务的 HTTP 客户端。服务间凭证走 Authorization 头，
 * 连接 / 读取超时从配置读取，任何失败都转成 {@link AiUnavailableException}，
 * 让上层快速降级，而不是把异常细节（内部地址、响应体）漏给浏览器。
 */
@Component
public class AiServiceClient {

    static final String UNAVAILABLE = "AI assistant is temporarily unavailable, please try again later";

    private final RestClient restClient;

    /** 只依赖配置直接构建：worker / seeder 等非 web 上下文没有 RestClient.Builder bean。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AiServiceClient(AppProperties properties) {
        this(build(properties));
    }

    /** 测试注入：允许用 MockRestServiceServer 绑定过的 builder 构造。 */
    AiServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient build(AppProperties properties) {
        AppProperties.Ai ai = properties.getAi();
        return RestClient.builder()
                .baseUrl(ai.getServiceUrl())
                .defaultHeader("Authorization", "Bearer " + ai.getServiceToken())
                .requestFactory(factory(ai))
                .build();
    }

    private static ClientHttpRequestFactory factory(AppProperties.Ai ai) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(ai.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(ai.getReadTimeoutMs()));
        return factory;
    }

    public ImproveEventResult improveEvent(ImproveEventPayload payload) {
        return execute(() -> restClient.post()
                .uri("/internal/v1/improve-event")
                .body(payload)
                .retrieve()
                .body(ImproveEventResult.class));
    }

    public DiscoveryResult discoveryChat(DiscoveryPayload payload) {
        return execute(() -> restClient.post()
                .uri("/internal/v1/discovery/chat")
                .body(payload)
                .retrieve()
                .body(DiscoveryResult.class));
    }

    private <T> T execute(java.util.function.Supplier<T> call) {
        try {
            T result = call.get();
            if (result == null) {
                throw new AiUnavailableException(UNAVAILABLE);
            }
            return result;
        }
        catch (AiUnavailableException e) {
            throw e;
        }
        catch (Exception e) {
            throw new AiUnavailableException(UNAVAILABLE);
        }
    }
}
