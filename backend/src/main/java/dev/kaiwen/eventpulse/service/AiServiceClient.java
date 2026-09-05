package dev.kaiwen.eventpulse.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.kaiwen.eventpulse.common.AppProperties;
import dev.kaiwen.eventpulse.dto.AiDtos.AiUsage;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryPayload;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryResult;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryStreamEvent;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryStreamEventRef;
import dev.kaiwen.eventpulse.dto.AiDtos.DiscoveryStreamResult;
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
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 只依赖配置直接构建：worker / seeder 等非 web 上下文没有 RestClient.Builder bean。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AiServiceClient(AppProperties properties) {
        this.properties = properties;
        this.restClient = build(properties);
    }

    /** 测试注入：允许用 MockRestServiceServer 绑定过的 builder 构造。 */
    AiServiceClient(RestClient restClient) {
        this.properties = null;
        this.restClient = restClient;
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

    /**
     * 发现助手的流式调用：POST 到 Python 的 SSE 端点，一边读一边把每条帧回调
     * 出去。阻塞在调用方提供的线程上（调用方负责用独立线程跑，别占 Tomcat 线程）。
     *
     * 帧类型：delta(文本) / result(权威收尾) / error(明确降级)。连接或解析失败
     * 转成 {@link AiUnavailableException}，让上层走与同步路径一致的降级。
     * 回调里抛出的 {@link StreamRelayAborted} 原样向上传播（调用方用于识别
     * 「浏览器断开」，与上游故障区分开）。
     */
    public void streamDiscoveryChat(
            DiscoveryPayload payload,
            Consumer<DiscoveryStreamEvent> consumer) {
        try {
            restClient.post()
                    .uri("/internal/v1/discovery/chat/stream")
                    .body(payload)
                    .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                    .exchange((request, response) -> consumeStream(response, consumer));
        }
        catch (AiUnavailableException e) {
            throw e;
        }
        catch (StreamRelayAborted e) {
            // 浏览器断开：不是上游故障，原样抛出由调用方记账。
            throw e;
        }
        catch (Exception e) {
            log.warn("ai streaming upstream call failed", e);
            throw new AiUnavailableException(UNAVAILABLE);
        }
    }

    /** 回调内部信号：浏览器连接已断开，转发无意义。 */
    public static final class StreamRelayAborted extends RuntimeException {
        public StreamRelayAborted(Throwable cause) {
            super(cause);
        }
    }

    /** 读完整个响应流并逐帧回调；任何读取错误都按不可用处理。 */
    private Object consumeStream(ClientHttpResponse response, Consumer<DiscoveryStreamEvent> consumer)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful()) {
            // 非 2xx：错误体通常是 JSON，但这里只关心状态码；细节进日志不泄漏。
            throw new AiUnavailableException(UNAVAILABLE);
        }
        InputStream body = response.getBody();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (eventName != null && data.length() > 0) {
                        DiscoveryStreamEvent parsed = parseEvent(eventName, data.toString());
                        // 未知事件 / 坏帧会被 parseEvent 忽略（返回 null）：不转发。
                        if (parsed != null) {
                            consumer.accept(parsed);
                        }
                    }
                    eventName = null;
                    data.setLength(0);
                }
                else if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                }
                else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim());
                }
            }
        }
        return null;
    }

    private DiscoveryStreamEvent parseEvent(String eventName, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            switch (eventName) {
                case "delta":
                    return DiscoveryStreamEvent.delta(node.path("text").asText(""));
                case "error":
                    return DiscoveryStreamEvent.error(node.path("message").asText(UNAVAILABLE));
                case "result":
                    return DiscoveryStreamEvent.result(toStreamResult(node));
                default:
                    return null;
            }
        }
        catch (Exception e) {
            // 单条坏帧不能毁掉整条流；但真正拿到 result 之前不能把流当成功。
            log.warn("ignoring unparsable ai stream frame {}: {}", eventName, json);
            return null;
        }
    }

    private DiscoveryStreamResult toStreamResult(JsonNode node) {
        java.util.List<DiscoveryStreamEventRef> refs = new java.util.ArrayList<>();
        for (JsonNode item : node.path("events")) {
            Long eventId = item.path("eventId").isNumber()
                    ? item.path("eventId").asLong() : null;
            String reason = item.path("reason").asText("");
            if (eventId != null) {
                refs.add(new DiscoveryStreamEventRef(eventId, reason));
            }
        }
        JsonNode usageNode = node.path("usage");
        AiUsage usage = new AiUsage(
                usageNode.path("inputTokens").isNumber() ? usageNode.path("inputTokens").asInt() : null,
                usageNode.path("outputTokens").isNumber() ? usageNode.path("outputTokens").asInt() : null);
        java.util.List<String> followUps = new java.util.ArrayList<>();
        for (JsonNode item : node.path("followUpQuestions")) {
            followUps.add(item.asText());
        }
        return new DiscoveryStreamResult(
                node.path("answer").asText(""),
                refs,
                followUps,
                node.path("provider").asText("unknown"),
                node.path("model").asText("unknown"),
                usage,
                node.path("toolCalls").isNumber() ? node.path("toolCalls").asInt() : null);
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
            // 异常细节（内部地址、响应体）不进响应，但必须进服务端日志，否则
            // Python 服务宕机 / 配错地址时排障零线索。凭证与提示词内容不在其中。
            log.warn("ai upstream call failed", e);
            throw new AiUnavailableException(UNAVAILABLE);
        }
    }
}
