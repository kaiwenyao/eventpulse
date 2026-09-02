package dev.kaiwen.eventpulse.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一次 AI 调用的结果记录：状态、耗时与可获取的 token 用量。
 * 不保存密钥，也不保存完整提示词与完整模型回复。
 */
@Entity
@Table(name = "ai_requests")
public class AiRequestLog {

    @Id
    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "user_id")
    private Long userId;

    /** improve-event / discovery。 */
    @Column(nullable = false, length = 40)
    private String feature;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    /** success / failure / unavailable。 */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "error_code", length = 120)
    private String errorCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
