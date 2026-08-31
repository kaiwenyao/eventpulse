# ADR-004 单活动支付意图 + 钱包扣款 + 退款额度预占

状态：已接受（2026-08 修订：去掉模拟支付网关，改为订单事务内钱包扣/退）

## 决策

**单飞（single flight）**

- `payment_intents.booking_id` 上建部分唯一索引（`WHERE active = TRUE`），
  一个 booking 同时只有一个活动意图；改变幂等键不能绕过。重复请求在仍为
  `PAYMENT_PENDING` 时返回既有意图；支付成功后订单已确认，第二次 `POST /pay`
  返回 `409 BOOKING_NOT_PAYABLE`。
- 支付、超时调度、取消请求都先锁同一 booking（协议 B），只有成功从
  PAYMENT_PENDING 条件迁移的一方修改库存/quota/权益。过期不扣钱包。

**钱包扣款（同一事务）**

1. `POST /pay` 按协议 B 锁 booking，再锁 quota / inventory / payment_balance /
   `user_wallet`。条件更新
   `UPDATE user_wallets SET available = available - amt WHERE available >= amt`。
2. 0 行 → `409 INSUFFICIENT_BALANCE`，订单仍为 `PAYMENT_PENDING`，到期释放库存。
3. 扣款成功：插入 intent `SUCCEEDED`（`active = FALSE`）、确认订单、出票、
   inventory reserved→sold、quota active→confirmed、写 outbox。客户端立即看到
   `CONFIRMED`。
4. 注册与 demo seed 写入 `user_wallets`，开户赠金
   `eventpulse.wallet.signup-grant-minor`（默认 1_000_000 minor / ¥10,000）。
   本仓库不做充值/提现 API。

不再在支付路径插入 CAPTURE/VOID/REFUND command，也不再调用隔离网关、UNKNOWN
查询或迟到 capture 补偿。`commands` 表与 admin retry/abandon 仍保留，供 NOTIFY
与预占残留运维。

**退款额度预占**

- `payment_balance(booking_id)` 单行三金额：captured / refund_reserved / refunded，
  行内 CHECK：`refund_reserved + refunded <= captured`。
- 取消确认订单时同一事务：撤销票券 → 条件预占 → 钱包 `available += amt` →
  reserved→refunded → 订单 `CANCELLED` / `REFUNDED`。客户端不再经过
  `CANCELLATION_PENDING` 等待网关。
- 无捕获金额时直接 `CANCELLED`。人工 abandon 仍可作为预占残留的运维出口。

## 验证

`BookingLifecycleIT`：双 key 单意图（第二次 pay 为 `BOOKING_NOT_PAYABLE`）；
确认后库存/票券/余额；pay vs expire 只有一方迁出且过期不扣钱包；余额不足 409
且库存仍 reserved；取消后钱包退回且 `refunded == captured`。
`TicketRedeemIT` 覆盖取消 vs 核销。

## 后果

- 支付正确性由数据库条件更新保证，不再依赖网关幂等或迟到补偿。
- 金额守恒仍由 CHECK + 条件更新双保险。
- 真实收单清算仍是非目标。
