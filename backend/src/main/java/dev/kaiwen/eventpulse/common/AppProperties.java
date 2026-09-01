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
    private String mediaDir = "data/media";
    private boolean redisEnabled = false;
    private String redisHost = "localhost";
    private int redisPort = 6379;

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

    public String getMediaDir() {
        return mediaDir;
    }

    public void setMediaDir(String mediaDir) {
        this.mediaDir = mediaDir;
    }

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    public String getRedisHost() {
        return redisHost;
    }

    public void setRedisHost(String redisHost) {
        this.redisHost = redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public void setRedisPort(int redisPort) {
        this.redisPort = redisPort;
    }
}
