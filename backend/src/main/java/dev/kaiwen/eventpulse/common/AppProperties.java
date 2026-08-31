package dev.kaiwen.eventpulse.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "eventpulse")
public class AppProperties {

    /**
     * HMAC-SHA256 至少 32 字节。演示环境用默认值，生产请用环境变量覆盖。
     */
    private String secretKey = "dev-only-secret-key-change-me-0123456789ab";
    private long tokenTtlMs = 7L * 24 * 3600 * 1000;
    private String corsOrigins = "http://localhost:3000,http://localhost:5173";

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public long getTokenTtlMs() {
        return tokenTtlMs;
    }

    public void setTokenTtlMs(long tokenTtlMs) {
        this.tokenTtlMs = tokenTtlMs;
    }

    public String getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(String corsOrigins) {
        this.corsOrigins = corsOrigins;
    }

    public String[] corsOriginArray() {
        return corsOrigins.split("\\s*,\\s*");
    }
}
