-- 分布式运行时改造：
-- 1) outbox 增加 message_key / claimed_by / claimed_until，支持多 Worker 领取与保序；
-- 2) 新增 seed_runs 表，Seeder（Kubernetes Job）安全重试，不产生重复数据。

-- message_key：稳定的业务标识（例如 booking:123）。决定 Kafka 分区与同键消息的
-- 先后顺序；与 dedup_key（消费端去重）用途不同。存量消息用 dedup_key 兜底。
ALTER TABLE outbox ADD COLUMN message_key VARCHAR(200);
UPDATE outbox SET message_key = dedup_key WHERE message_key IS NULL;
ALTER TABLE outbox ALTER COLUMN message_key SET NOT NULL;

-- 领取租约：claimed_by 记录领取方（workerId + 一次性 token），
-- claimed_until 到期后其他 Worker 可以接手，Worker 意外退出不会永久卡住消息。
ALTER TABLE outbox ADD COLUMN claimed_by    VARCHAR(100);
ALTER TABLE outbox ADD COLUMN claimed_until TIMESTAMPTZ;

CREATE INDEX ix_outbox_claimable ON outbox (id)
    WHERE published_at IS NULL AND failed_at IS NULL;

-- 播种执行记录：seed_name 唯一。Seeder 在同一事务里完成播种并写入本表，
-- 失败回滚不留记录，Job 重试或人工重跑不会重复写数据。
CREATE TABLE seed_runs (
    seed_name    VARCHAR(100) NOT NULL,
    completed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (seed_name)
);
