-- 用户账户余额：个人中心展示与演示用「钱包」。
-- 维护在单个 Bigint 列上而不是独立流水表，保持简单；余额变动在事务内原子更新。
ALTER TABLE users ADD COLUMN wallet_cents BIGINT NOT NULL DEFAULT 0;