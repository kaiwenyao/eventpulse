-- EventPulse 基线 schema。
--
-- 上线前把原来的 V1..V9 合并成这一份：迁移历史里已经没有需要保留的中间状态，
-- 数据库随时可以重建。之后的结构变更继续按 V2、V3…… 顺序追加，不要再改这个文件。

CREATE TABLE users (
    id           BIGSERIAL PRIMARY KEY,
    email        VARCHAR(255) NOT NULL UNIQUE,
    password     VARCHAR(255) NOT NULL,
    name         VARCHAR(100) NOT NULL,
    role         VARCHAR(20)  NOT NULL,
    -- 账户余额：个人中心展示与演示用「钱包」，变动在事务内原子更新。
    wallet_cents BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE events (
    id                       BIGSERIAL PRIMARY KEY,
    title                    VARCHAR(200) NOT NULL,
    summary                  VARCHAR(300),
    description              TEXT,
    category                 VARCHAR(50)  NOT NULL,
    city                     VARCHAR(50)  NOT NULL,
    venue_name               VARCHAR(200),
    address                  VARCHAR(400),
    latitude                 DOUBLE PRECISION,
    longitude                DOUBLE PRECISION,
    starts_at                TIMESTAMPTZ  NOT NULL,
    ends_at                  TIMESTAMPTZ  NOT NULL,
    cover_url                VARCHAR(500),
    cover_asset_id           BIGINT,
    sales_start_at           TIMESTAMPTZ,
    sales_end_at             TIMESTAMPTZ,
    max_quantity_per_booking INT          NOT NULL DEFAULT 10,
    contact_info             VARCHAR(300),
    attendance_notes         TEXT,
    price_cents              INT          NOT NULL,
    capacity                 INT          NOT NULL,
    sold                     INT          NOT NULL DEFAULT 0,
    organiser_id             BIGINT       NOT NULL REFERENCES users (id),
    status                   VARCHAR(20)  NOT NULL,
    cancellation_reason      VARCHAR(500),
    cancelled_at             TIMESTAMPTZ,
    archive_note             VARCHAR(500),
    archived_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Hibernate 把 @Version long 映射成 BIGINT。
    version                  BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE bookings (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users (id),
    event_id       BIGINT      NOT NULL REFERENCES events (id),
    quantity       INT         NOT NULL,
    -- 下单时从钱包实际扣除的金额，作为退款金额的不可变快照。
    paid_cents     BIGINT      NOT NULL DEFAULT 0,
    status         VARCHAR(20) NOT NULL,
    cancelled_at   TIMESTAMPTZ,
    organiser_note VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT,
    event_id   BIGINT,
    booking_id BIGINT,
    type       VARCHAR(50)  NOT NULL,
    title      VARCHAR(200),
    message    VARCHAR(500) NOT NULL,
    payload    TEXT,
    dedup_key  VARCHAR(200),
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_notifications_dedup_key ON notifications (dedup_key) WHERE dedup_key IS NOT NULL;

CREATE TABLE event_audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT      NOT NULL REFERENCES events (id),
    operator_id BIGINT      NOT NULL,
    action      VARCHAR(50) NOT NULL,
    before_data TEXT,
    after_data  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE event_favourites (
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    event_id   BIGINT      NOT NULL REFERENCES events (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_id)
);

CREATE TABLE tickets (
    id                 BIGSERIAL PRIMARY KEY,
    booking_id         BIGINT      NOT NULL REFERENCES bookings (id),
    event_id           BIGINT      NOT NULL REFERENCES events (id),
    ticket_code_hash   VARCHAR(64) NOT NULL UNIQUE,
    ticket_code_cipher TEXT        NOT NULL,
    status             VARCHAR(20) NOT NULL,
    checked_in_at      TIMESTAMPTZ,
    checked_in_by      BIGINT,
    check_in_source    VARCHAR(50),
    revoked_at         TIMESTAMPTZ,
    revoked_by         BIGINT,
    revocation_reason  VARCHAR(300),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE media_assets (
    id           BIGSERIAL PRIMARY KEY,
    owner_id     BIGINT       NOT NULL REFERENCES users (id),
    storage_key  VARCHAR(300) NOT NULL,
    public_url   VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);

CREATE TABLE user_preferences (
    user_id    BIGINT PRIMARY KEY REFERENCES users (id),
    categories VARCHAR(300),
    cities     VARCHAR(300),
    latitude   DOUBLE PRECISION,
    longitude  DOUBLE PRECISION,
    radius_km  DOUBLE PRECISION,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE interactions (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    event_id   BIGINT      NOT NULL REFERENCES events (id),
    type       VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Outbox：业务事务里写消息，后台 Worker 领取后再投递到 Kafka。
-- message_key 是稳定的业务标识（例如 booking:123），决定 Kafka 分区与同键消息的先后顺序；
-- dedup_key 用于消费端去重，两者用途不同。
-- claimed_by / claimed_until 是领取租约：租约到期后其他 Worker 可以接手，
-- Worker 意外退出不会永久卡住消息。
CREATE TABLE outbox (
    id               BIGSERIAL PRIMARY KEY,
    topic            VARCHAR(80)   NOT NULL,
    event_type       VARCHAR(80)   NOT NULL,
    payload          TEXT          NOT NULL,
    dedup_key        VARCHAR(200)  NOT NULL,
    message_key      VARCHAR(200)  NOT NULL,
    claimed_by       VARCHAR(100),
    claimed_until    TIMESTAMPTZ,
    publish_attempts INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(1000),
    failed_at        TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ
);

-- 待发送队列：已隔离（failed_at 非空）的坏消息不出现在索引里，不会堵住后面的消息。
CREATE INDEX ix_outbox_claimable ON outbox (id)
    WHERE published_at IS NULL AND failed_at IS NULL;

-- 记录哪些 Kafka 消息已经被 Consumer 完整处理过：即使 Outbox 重复发送也只处理一次。
CREATE TABLE consumed_events (
    consumer_group VARCHAR(100) NOT NULL,
    dedup_key      VARCHAR(200) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, dedup_key)
);

CREATE TABLE event_daily_metrics (
    event_id    BIGINT NOT NULL REFERENCES events (id),
    metric_date DATE   NOT NULL,
    views       INT    NOT NULL DEFAULT 0,
    clicks      INT    NOT NULL DEFAULT 0,
    saves       INT    NOT NULL DEFAULT 0,
    unsaves     INT    NOT NULL DEFAULT 0,
    bookings    INT    NOT NULL DEFAULT 0,
    tickets     INT    NOT NULL DEFAULT 0,
    cancels     INT    NOT NULL DEFAULT 0,
    check_ins   INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, metric_date)
);

-- 播种执行记录：seed_name 唯一。Seeder 在同一事务里完成播种并写入本表，
-- 失败回滚不留记录，Job 重试或人工重跑不会重复写数据。
CREATE TABLE seed_runs (
    seed_name    VARCHAR(100) NOT NULL,
    completed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (seed_name)
);

-- AI 会话由 Spring Boot 持久化到 PostgreSQL，Python AI 服务不在进程内存保存会话，
-- 因此 AI 服务可以多副本。ai_messages 只保存用户可见的提问与回答，不保存模型内部思考过程。
CREATE TABLE ai_conversations (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    kind       VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_ai_conversations_user ON ai_conversations (user_id, updated_at DESC);

CREATE TABLE ai_messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES ai_conversations (id),
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
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

CREATE INDEX ix_events_organiser_status ON events (organiser_id, status);
CREATE INDEX ix_events_public_starts ON events (status, starts_at);
CREATE INDEX ix_tickets_event_status ON tickets (event_id, status);
CREATE INDEX ix_bookings_event ON bookings (event_id);
