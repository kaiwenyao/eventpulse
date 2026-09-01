-- Outbox 可靠性改造：给 outbox 增加发送失败计数、错误信息与隔离时间。
-- 一条永远发不出去的坏消息会被隔离（failed_at 非空），不会堵住后面的消息。
ALTER TABLE outbox ADD COLUMN publish_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE outbox ADD COLUMN last_error       VARCHAR(1000);
ALTER TABLE outbox ADD COLUMN failed_at        TIMESTAMPTZ;

-- 已隔离的消息不该再出现在待发送索引里。
DROP INDEX ix_outbox_unpublished;
CREATE INDEX ix_outbox_unpublished ON outbox (id)
    WHERE published_at IS NULL AND failed_at IS NULL;
