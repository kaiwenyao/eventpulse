-- V2：购物车、钱包流水与批量结算幂等。
-- 只追加：不改 V1 已有列的定义；新列全部可空或带默认值，老数据无需回填即可启动。

-- ---------------------------------------------------------------------------
-- 1) users：账户内流水序号。
--    每次余额变动与 ledger_seq + 1 在同一条原子 UPDATE 里完成，
--    钱包行锁保证并发交易下 (user_id, seq_no) 单调且不重复，
--    流水链（变动前 + 变动额 = 变动后）才能按 seq 顺序核对。
-- ---------------------------------------------------------------------------
ALTER TABLE users ADD COLUMN ledger_seq BIGINT NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------
-- 2) checkouts：一次批量结算（含带幂等键的直接下单）。
--    幂等键按 (user_id, idempotency_key) 唯一：结算行与整个结算事务一起提交，
--    事务回滚时幂等键行一并消失，同一键的重试可以重新结算；
--    结算成功后重试会命中本表，直接返回原订单。
-- ---------------------------------------------------------------------------
CREATE TABLE checkouts (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    idempotency_key VARCHAR(120) NOT NULL,
    -- 规范化请求参数（排序后的 eventId + quantity 列表）的 SHA-256。
    -- 同一键配不同参数直接拒绝，不返回别人的结算结果。
    request_hash    VARCHAR(64)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'SUCCEEDED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_checkouts_user_key ON checkouts (user_id, idempotency_key);

-- ---------------------------------------------------------------------------
-- 3) bookings：展示快照与结算关联。
--    unit_price_cents 是下单单价快照；老订单从不可变的实付快照推导
--    （paid_cents 恒等于 price * quantity，推导不是伪造），避免展示时再算。
--    checkout_id 关联同一次购物车结算；直接下单（无幂等键）保持为空。
-- ---------------------------------------------------------------------------
ALTER TABLE bookings ADD COLUMN unit_price_cents BIGINT;
ALTER TABLE bookings ADD COLUMN checkout_id BIGINT;

UPDATE bookings SET unit_price_cents = paid_cents / quantity;

ALTER TABLE bookings
    ADD CONSTRAINT fk_bookings_checkout FOREIGN KEY (checkout_id) REFERENCES checkouts (id);

-- 历史订单列表：按用户分页 + 稳定次级排序（created_at DESC, id DESC）。
CREATE INDEX ix_bookings_user_id_id ON bookings (user_id, id DESC);
-- 同次结算的关联订单。
CREATE INDEX ix_bookings_checkout ON bookings (checkout_id) WHERE checkout_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4) wallet_ledger：余额流水（只追加，不修改不删除）。
--    amount_cents 带正负号；balance_before + amount = balance_after 由
--    WalletService 用同一条 UPDATE ... RETURNING 的结果写入并受并发控制。
--    external_biz_id 全局唯一：业务层去重（同一订单最多一笔退款等），
--    竞争下后到的事务因唯一约束整体回滚，不会重复记账。
-- ---------------------------------------------------------------------------
CREATE TABLE wallet_ledger (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES users (id),
    -- RECHARGE / BOOKING_PAYMENT / BOOKING_REFUND / EVENT_CANCEL_REFUND / OPENING_BALANCE
    biz_type             VARCHAR(30)  NOT NULL,
    amount_cents         BIGINT       NOT NULL CHECK (amount_cents <> 0),
    balance_before_cents BIGINT       NOT NULL CHECK (balance_before_cents >= 0),
    balance_after_cents  BIGINT       NOT NULL CHECK (balance_after_cents >= 0),
    booking_id           BIGINT       REFERENCES bookings (id),
    checkout_id          BIGINT       REFERENCES checkouts (id),
    -- 业务去重标识，如 RECHARGE:<key> / PAY:<bookingId> / REFUND:<bookingId> / OPENING_BALANCE:<userId>
    external_biz_id      VARCHAR(120) NOT NULL,
    description          VARCHAR(500),
    seq_no               BIGINT       NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, seq_no)
);

CREATE UNIQUE INDEX ux_wallet_ledger_biz ON wallet_ledger (external_biz_id);
CREATE INDEX ix_wallet_ledger_user_id_id ON wallet_ledger (user_id, id DESC);
CREATE INDEX ix_wallet_ledger_booking ON wallet_ledger (booking_id) WHERE booking_id IS NOT NULL;

-- 期初余额：给迁移时余额非 0 的老账户写一条明确标记的期初记录。
-- 只声明「迁移时余额是多少」，不猜测此前的充值 / 消费明细（不可追溯）；
-- 不增加也不扣除余额（balance_after = 迁移时的 wallet_cents）。
-- 迁移前的老订单之后仍可正常退款：退款只依赖订单上的 paid_cents 快照，
-- 不需要存在对应的旧扣款流水。
INSERT INTO wallet_ledger (user_id, biz_type, amount_cents, balance_before_cents,
                           balance_after_cents, external_biz_id, description, seq_no, created_at)
SELECT id,
       'OPENING_BALANCE',
       wallet_cents,
       0,
       wallet_cents,
       'OPENING_BALANCE:' || id,
       'Opening balance when the wallet ledger was introduced. Details before this record are not traceable.',
       1,
       now()
FROM users
WHERE wallet_cents <> 0;

UPDATE users SET ledger_seq = 1 WHERE wallet_cents <> 0;

-- ---------------------------------------------------------------------------
-- 5) cart_items：购物车（数据库持久化，换设备 / 重新登录仍在）。
--    同一用户同一活动合并为一行；price_cents 是加购时的价格快照，
--    用于发现「价格已变化」并要求用户重新确认，不静默按新价格扣款。
-- ---------------------------------------------------------------------------
CREATE TABLE cart_items (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id),
    event_id    BIGINT      NOT NULL REFERENCES events (id),
    quantity    INT         NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    selected    BOOLEAN     NOT NULL DEFAULT TRUE,
    price_cents INT         NOT NULL CHECK (price_cents >= 0),
    -- 每次变更 +1：cart-events 带上它，消费端据此丢弃乱序 / 旧版本事件。
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, event_id)
);

CREATE INDEX ix_cart_items_user_id ON cart_items (user_id, updated_at DESC);

-- ---------------------------------------------------------------------------
-- 6) cart 消费统计（worker 从 cart-events 汇总，明确口径）：
--    items_added / quantity_added 只统计「新加入购物车」的行（合并进已有行
--    不重复计数），items_removed / quantity_removed 统计主动移除与清空，
--    checkouts / purchased_* 只按 CART_CHECKOUT_COMPLETED 汇总事件累计一次。
--    异步统计有延迟，不用于余额 / 库存判断。
-- ---------------------------------------------------------------------------
CREATE TABLE cart_daily_stats (
    stat_date              DATE   PRIMARY KEY,
    items_added            BIGINT NOT NULL DEFAULT 0,
    quantity_added         BIGINT NOT NULL DEFAULT 0,
    items_removed          BIGINT NOT NULL DEFAULT 0,
    quantity_removed       BIGINT NOT NULL DEFAULT 0,
    checkouts              BIGINT NOT NULL DEFAULT 0,
    purchased_quantity     BIGINT NOT NULL DEFAULT 0,
    purchased_amount_cents BIGINT NOT NULL DEFAULT 0
);

-- 消费端按版本丢弃旧事件：记录每个购物车项已统计到的最大 version。
CREATE TABLE cart_seen_item_versions (
    item_id      BIGINT PRIMARY KEY,
    last_version BIGINT NOT NULL
);
