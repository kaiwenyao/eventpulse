-- 新版 AI（外部 LLM + LangChain Agent）落地，同时移除旧版规则推荐残留：
-- 1) 删除旧推荐专用表 recommendation_requests（V2 引入，只被已删除的
--    PlatformService.recommend() / GET /api/recommendations 使用）。
--    已执行过旧 migration 的环境通过本迁移删除；不回改已发布的 V2。
-- 2) 新增 AI 会话 / 消息 / 调用记录表：会话由 Spring Boot 持久化到
--    PostgreSQL，Python AI 服务不在进程内存保存会话，因此后续可以多副本。
--    ai_messages 只保存用户可见的提问与回答，不保存模型内部思考过程。

DROP TABLE IF EXISTS recommendation_requests;

CREATE TABLE ai_conversations (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    kind       VARCHAR(30)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_ai_conversations_user ON ai_conversations (user_id, updated_at DESC);

CREATE TABLE ai_messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT       NOT NULL REFERENCES ai_conversations (id),
    role            VARCHAR(20)  NOT NULL,
    content         TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_ai_messages_conversation ON ai_messages (conversation_id, id);

-- 每次调用 AI 服务记录一条：结果状态、耗时与可获取的 token 用量。
-- 不保存密钥，也不保存完整提示词与完整模型回复。
CREATE TABLE ai_requests (
    request_id    VARCHAR(64)  NOT NULL PRIMARY KEY,
    user_id       BIGINT,
    feature       VARCHAR(40)  NOT NULL,
    provider      VARCHAR(40)  NOT NULL,
    model_name    VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    error_code    VARCHAR(120),
    latency_ms    INT,
    input_tokens  INT,
    output_tokens INT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_ai_requests_created ON ai_requests (created_at);
