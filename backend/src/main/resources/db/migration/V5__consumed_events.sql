-- Outbox 可靠性改造：记录哪些 Kafka 消息已经被 Consumer 完整处理过。
-- 同一条消息即使被 Outbox 重复发送，也只会被处理一次。
CREATE TABLE consumed_events (
    consumer_group VARCHAR(100) NOT NULL,
    dedup_key      VARCHAR(200) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, dedup_key)
);
