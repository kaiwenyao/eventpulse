-- 保存下单时从钱包实际扣除的金额，作为退款金额的不可变快照。
-- 当前尚未上线，已有订单视作免费订单以便安全迁移；新订单都会写入真实金额。
ALTER TABLE bookings ADD COLUMN paid_cents BIGINT NOT NULL DEFAULT 0;
