-- ---------------------------------------------------------------------------
-- 购物车列表排序键：updated_at -> created_at
--
-- 修复：购物车此前按 updated_at DESC 排序，而改数量 / 勾选 / 价格确认
-- 都会刷新 updated_at，导致刚操作过的物品跳到列表最前，相对顺序变化。
-- 改为按加购时间倒序（created_at DESC, id DESC）：created_at 只在首次加购
-- 时写入，任何后续操作都不再移动行；id 兜底保证同一时刻加购的行顺序确定。
-- 索引跟着查询的 ORDER BY 一起换，user_id 前缀保持不变。
-- ---------------------------------------------------------------------------

DROP INDEX ix_cart_items_user_id;

CREATE INDEX ix_cart_items_user_id ON cart_items (user_id, created_at DESC, id DESC);