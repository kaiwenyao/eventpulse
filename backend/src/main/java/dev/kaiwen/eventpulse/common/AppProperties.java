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
    private final S3 s3 = new S3();
    private final Media media = new Media();

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
        /**
         * 每日 token 预算（0 = 关闭）。与「次/分钟」限流互补：限流挡短时间刷调用，
         * 预算挡长尾的成本失控。语义是「先检查、后记账」，所以跨过阈值的那一次请求
         * 仍会放行并小幅超支 —— 这是成本护栏，不是计费闸门。
         */
        private int dailyTokenBudgetUser = 200000;
        private int dailyTokenBudgetGlobal = 0;
        /**
         * 上游失败时按这个值记账。失败的那一轮（尤其是跑满工具循环后才超时的）真的
         * 烧了 token，但 Python 的 502 里没有 usage；不记账的话，能稳定触发失败的
         * 用户等于拥有无限预算。
         */
        private int failureTokenPenalty = 2000;
        /** AI 结果缓存总开关；没有 Redis 时无论如何都不缓存。 */
        private boolean cacheEnabled = true;
        private int cacheImproveTtlSeconds = 3600;
        private int cacheDiscoveryTtlSeconds = 120;

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

        public int getDailyTokenBudgetUser() {
            return dailyTokenBudgetUser;
        }

        public void setDailyTokenBudgetUser(int dailyTokenBudgetUser) {
            this.dailyTokenBudgetUser = dailyTokenBudgetUser;
        }

        public int getDailyTokenBudgetGlobal() {
            return dailyTokenBudgetGlobal;
        }

        public void setDailyTokenBudgetGlobal(int dailyTokenBudgetGlobal) {
            this.dailyTokenBudgetGlobal = dailyTokenBudgetGlobal;
        }

        public int getFailureTokenPenalty() {
            return failureTokenPenalty;
        }

        public void setFailureTokenPenalty(int failureTokenPenalty) {
            this.failureTokenPenalty = failureTokenPenalty;
        }

        public boolean isCacheEnabled() {
            return cacheEnabled;
        }

        public void setCacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
        }

        public int getCacheImproveTtlSeconds() {
            return cacheImproveTtlSeconds;
        }

        public void setCacheImproveTtlSeconds(int cacheImproveTtlSeconds) {
            this.cacheImproveTtlSeconds = cacheImproveTtlSeconds;
        }

        public int getCacheDiscoveryTtlSeconds() {
            return cacheDiscoveryTtlSeconds;
        }

        public void setCacheDiscoveryTtlSeconds(int cacheDiscoveryTtlSeconds) {
            this.cacheDiscoveryTtlSeconds = cacheDiscoveryTtlSeconds;
        }
    }

    /**
     * 图片对象存储（SeaweedFS S3 / 任意 S3 兼容服务）。enabled=false 时回落到
     * 本地磁盘 mediaDir（仅本地开发）；生产与多副本部署必须启用 S3，否则
     * api 副本之间看不到彼此上传的文件。凭证只从环境变量 / Secret 注入，
     * 不落库、不打日志。
     */
    public static class S3 {

        /** 总开关：关闭时图片走本地磁盘（eventpulse.media-dir）。 */
        private boolean enabled = false;
        /** S3 兼容服务地址，例如 https://s3.kaiwen.dev（SeaweedFS）。 */
        private String endpoint = "";
        private String region = "us-east-1";
        /** 启动/运行时不会创建 bucket，必须已存在。 */
        private String bucket = "eventpulse";
        /**
         * 图片的浏览器直连基址，例如 https://s3.kaiwen.dev/eventpulse。
         * 只有 bucket 已授予匿名 GetObject 时才配；留空则图片继续走
         * /api/media/images/{id} 代理（字节经过 api）。应用不改这个权限。
         */
        private String publicBaseUrl = "";
        private String accessKey = "";
        private String secretKey = "";
        /** SeaweedFS 走 path-style（https://endpoint/bucket/key），不开虚拟主机域名。 */
        private boolean pathStyleAccess = true;
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 10000;
        /** 单次 API 调用总超时（含重试），覆盖签名与网络抖动。 */
        private int apiCallTimeoutMs = 30000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
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

        public int getApiCallTimeoutMs() {
            return apiCallTimeoutMs;
        }

        public void setApiCallTimeoutMs(int apiCallTimeoutMs) {
            this.apiCallTimeoutMs = apiCallTimeoutMs;
        }
    }

    /**
     * 软删除后的 S3 对象清理（worker Profile 定时任务）。对象不随 DELETE 请求
     * 立即删除：软删除只改数据库审计字段，宽限期过后由 worker 统一清理对象，
     * 误删可在宽限期内恢复数据库状态找回。
     */
    public static class Media {

        private boolean purgeEnabled = true;
        /** 软删除到真正删除对象的宽限期（天）。 */
        private int purgeAfterDays = 7;
        /** 每轮最多清理的对象数，失败的对象跳过等下一轮。 */
        private int purgeBatchSize = 50;
        private long purgeFixedDelayMs = 3600000L;

        public boolean isPurgeEnabled() {
            return purgeEnabled;
        }

        public void setPurgeEnabled(boolean purgeEnabled) {
            this.purgeEnabled = purgeEnabled;
        }

        public int getPurgeAfterDays() {
            return purgeAfterDays;
        }

        public void setPurgeAfterDays(int purgeAfterDays) {
            this.purgeAfterDays = purgeAfterDays;
        }

        public int getPurgeBatchSize() {
            return purgeBatchSize;
        }

        public void setPurgeBatchSize(int purgeBatchSize) {
            this.purgeBatchSize = purgeBatchSize;
        }

        public long getPurgeFixedDelayMs() {
            return purgeFixedDelayMs;
        }

        public void setPurgeFixedDelayMs(long purgeFixedDelayMs) {
            this.purgeFixedDelayMs = purgeFixedDelayMs;
        }
    }

    public Ai getAi() {
        return ai;
    }

    public S3 getS3() {
        return s3;
    }

    public Media getMedia() {
        return media;
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
