package dev.kaiwen.eventpulse.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryResult;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventPayload;
import dev.kaiwen.eventpulse.dto.AiDtos.ImproveEventResult;

import jakarta.annotation.PostConstruct;

/**
 * Spring Boot → Python AI 服务的 HTTP 客户端。服务间凭证走 Authorization 头，
 * 连接 / 读取超时从配置读取，任何失败都转成 {@link AiUnavailableException}，
 * 让上层快速降级，而不是把异常细节（内部地址、响应体）漏给浏览器。
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    static final String UNAVAILABLE = "AI assistant is temporarily unavailable, please try again later";

    private final AppProperties properties;
    /**
     * 两条链的读超时差别很大，所以各建一个客户端：文案助手只有一次受
     * llm_timeout_seconds=30 + max_retries=0 约束的 LLM 调用，给它 90 秒纯属让
     * Tomcat 线程白占；发现助手一轮可能包含多次 LLM 调用与工具往返，确实需要长超时。
     */
    private final RestClient improveClient;
    private final RestClient discoveryClient;
    private final AiCircuitBreaker breaker;

    /** 只依赖配置直接构建：worker / seeder 等非 web 上下文没有 RestClient.Builder bean。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AiServiceClient(AppProperties properties) {
        this.properties = properties;
        this.improveClient = build(properties, properties.getAi().getReadTimeoutImproveMs());
        this.discoveryClient = build(properties, properties.getAi().getReadTimeoutMs());
        this.breaker = new AiCircuitBreaker(properties.getAi().getBreakerFailureThreshold(),
                properties.getAi().getBreakerOpenSeconds() * 1000L);
    }

    /** 测试注入：允许用 MockRestServiceServer 绑定过的 builder 构造。 */
    AiServiceClient(RestClient restClient) {
        this(restClient, AiCircuitBreaker.disabled());
    }

    /**
     * 测试注入 + 显式熔断器。熔断器必须由外部传入而不是从 properties 读：
     * 这个构造器把 properties 置空，任何 properties.getAi() 都会 NPE，而 execute()
     * 的 catch (Exception) 会把它吞成 AiUnavailableException —— 排查起来极其痛苦。
     */
    AiServiceClient(RestClient restClient, AiCircuitBreaker breaker) {
        this.properties = null;
        this.improveClient = restClient;
        this.discoveryClient = restClient;
        this.breaker = breaker;
    }

    /** 凭证配成空串或仍是仓库公开的 dev 默认值时给出与
     * {@link dev.kaiwen.eventpulse.interceptor.InternalServiceInterceptor} 一致的信号：
     * 空串启动即失败，dev 默认值告警（测试注入路径不校验）。 */
    @PostConstruct
    void validateConfiguration() {
        if (properties == null) {
            return;
        }
        String token = properties.getAi().getServiceToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "eventpulse.ai.service-token is blank: calls to the AI service would be unauthenticated");
        }
        if (AppProperties.Ai.DEV_DEFAULT_SERVICE_TOKEN.equals(token)) {
            log.warn("eventpulse.ai.service-token is still the public dev default; "
                    + "set AI_SERVICE_TOKEN before any shared deployment");
        }
    }

    private static RestClient build(AppProperties properties, int readTimeoutMs) {
        AppProperties.Ai ai = properties.getAi();
        return RestClient.builder()
                .baseUrl(ai.getServiceUrl())
                .defaultHeader("Authorization", "Bearer " + ai.getServiceToken())
                .requestFactory(factory(ai, readTimeoutMs))
                .build();
    }

    private static ClientHttpRequestFactory factory(AppProperties.Ai ai, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(ai.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }

    public ImproveEventResult improveEvent(ImproveEventPayload payload) {
        return execute(() -> improveClient.post()
                .uri("/internal/v1/improve-event")
                .body(payload)
                .retrieve()
                .body(ImproveEventResult.class));
    }

    public DiscoveryResult discoveryChat(DiscoveryPayload payload) {
        return execute(() -> discoveryClient.post()
                .uri("/internal/v1/discovery/chat")
                .body(payload)
                .retrieve()
                .body(DiscoveryResult.class));
    }

    /** 熔断是否已打开（供网关计指标）。 */
    public boolean isCircuitOpen() {
        return breaker.isOpen();
    }

    private <T> T execute(java.util.function.Supplier<T> call) {
        if (!breaker.allowRequest()) {
            // 上游确实躺了：立刻降级，不再让每个请求都挂满读超时才释放 Tomcat 线程。
            throw new AiUnavailableException(UNAVAILABLE);
        }
        try {
            T result = call.get();
            if (result == null) {
                throw new AiUnavailableException(UNAVAILABLE);
            }
            breaker.recordSuccess();
            return result;
        }
        catch (AiUnavailableException e) {
            throw e;
        }
        catch (Exception e) {
            // 异常细节（内部地址、响应体）不进响应，但必须进服务端日志，否则
            // Python 服务宕机 / 配错地址时排障零线索。凭证与提示词内容不在其中。
            log.warn("ai upstream call failed", e);
            if (isTransportFailure(e)) {
                breaker.recordFailure();
            }
            else {
                // 应用层错误（Python 的 502：模型没吐好、Agent 超预算）说明进程是活的，
                // 不该把它算进熔断，否则连续几次模型抽风就熔断掉健康的服务。
                breaker.recordSuccess();
            }
            throw new AiUnavailableException(UNAVAILABLE);
        }
    }

    /** 只有连不上 / 读超时 / 503 / 504 才算上游躺了。 */
    private static boolean isTransportFailure(Exception e) {
        if (e instanceof org.springframework.web.client.ResourceAccessException) {
            return true;
        }
        if (e instanceof org.springframework.web.client.HttpServerErrorException server) {
            int status = server.getStatusCode().value();
            return status == 503 || status == 504;
        }
        return false;
    }
}
