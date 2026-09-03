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
    private final Ai ai = new Ai();

    /**
     * AI 网关配置：浏览器只访问 Spring Boot，Spring Boot 调用独立 Python AI
     * 服务；Agent 需要业务数据时再带着服务凭证和短期签名的用户上下文回来调
     * /internal/ai-tools/**。这里没有 LLM API Key —— Key 只保存在 Python 服务的
     * Secret 中。
     */
    public static class Ai {

        /** 仓库内公开的 dev 默认凭证：仅限本地开发，共享部署必须覆盖，启动时会告警。 */
        public static final String DEV_DEFAULT_SERVICE_TOKEN = "dev-ai-service-token";
        public static final String DEV_DEFAULT_INTERNAL_TOKEN = "dev-ai-internal-token";

        /** 总开关：关闭或 Python 服务不可用时，AI 接口返回明确降级提示，普通业务不受影响。 */
        private boolean enabled = true;
        private String serviceUrl = "http://localhost:8090";
        /** Spring Boot → Python AI 服务的服务间凭证。 */
        private String serviceToken = DEV_DEFAULT_SERVICE_TOKEN;
        /** Python AI 服务 → Spring Boot /internal/ai-tools/** 的服务间凭证。 */
        private String internalToken = DEV_DEFAULT_INTERNAL_TOKEN;
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 90000;
        /** 用户 / IP 级限流（次/分钟），避免成本失控。 */
        private int rateLimitUserPerMinute = 10;
        private int rateLimitIpPerMinute = 30;
        private int maxMessageChars = 1000;
        private int maxEvents = 10;
        private int historyLimit = 8;
        /** 发给 Python 的用户上下文 token 有效期。 */
        private int contextTokenTtlSeconds = 300;
        /** Agent 解析「这个周末」这类相对日期时使用的明确时区。 */
        private String timeZone = "Europe/Berlin";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getServiceUrl() {
            return serviceUrl;
        }

        public void setServiceUrl(String serviceUrl) {
            this.serviceUrl = serviceUrl;
        }

        public String getServiceToken() {
            return serviceToken;
        }

        public void setServiceToken(String serviceToken) {
            this.serviceToken = serviceToken;
        }

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getRateLimitUserPerMinute() {
            return rateLimitUserPerMinute;
        }

        public void setRateLimitUserPerMinute(int rateLimitUserPerMinute) {
            this.rateLimitUserPerMinute = rateLimitUserPerMinute;
        }

        public int getRateLimitIpPerMinute() {
            return rateLimitIpPerMinute;
        }

        public void setRateLimitIpPerMinute(int rateLimitIpPerMinute) {
            this.rateLimitIpPerMinute = rateLimitIpPerMinute;
        }

        public int getMaxMessageChars() {
            return maxMessageChars;
        }

        public void setMaxMessageChars(int maxMessageChars) {
            this.maxMessageChars = maxMessageChars;
        }

        public int getMaxEvents() {
            return maxEvents;
        }

        public void setMaxEvents(int maxEvents) {
            this.maxEvents = maxEvents;
        }

        public int getHistoryLimit() {
            return historyLimit;
        }

        public void setHistoryLimit(int historyLimit) {
            this.historyLimit = historyLimit;
        }

        public int getContextTokenTtlSeconds() {
            return contextTokenTtlSeconds;
        }

        public void setContextTokenTtlSeconds(int contextTokenTtlSeconds) {
            this.contextTokenTtlSeconds = contextTokenTtlSeconds;
        }

        public String getTimeZone() {
            return timeZone;
        }

        public void setTimeZone(String timeZone) {
            this.timeZone = timeZone;
        }
    }

    public Ai getAi() {
        return ai;
    }

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
