-- ---------------------------------------------------------------------------
-- V5：AI 会话保留期清理所需的索引
--
-- 新增：AiRetentionWorker 按「全局 updated_at < cutoff」扫描过期会话。
-- V1 建的 ix_ai_conversations_user 是 (user_id, updated_at DESC)，首列是
-- user_id，这种不带 user_id 条件的全局范围扫描完全用不上它，会退化成全表扫。
--
-- ai_requests 不需要新索引：V1 已有的 ix_ai_requests_created (created_at)
-- 正好覆盖同一个 worker 的日志清理，token 预算走的是 Redis 日计数、不查这张表。
-- ---------------------------------------------------------------------------

-- 1. 保留期清理的扫描索引。
CREATE INDEX ix_ai_conversations_updated ON ai_conversations (updated_at);
