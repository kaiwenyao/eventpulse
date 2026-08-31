# ADR-004 单活动支付意图 + 命令租约 + 退款额度预占

状态：已接受

## 决策

**单飞（single flight）**

- `payment_intents.booking_id` 上建部分唯一索引（`WHERE active = TRUE`），
  一个 booking 同时只有一个活动意图；改变幂等键不能绕过。重复请求返回既有意图。
- 支付结果、超时调度、取消请求都先锁同一 booking（协议 B），只有成功从
  PAYMENT_PENDING 条件迁移的一方修改库存/quota/权益。

**Durable Command（可租约）**

1. 业务事务只插入 command（CAPTURE/VOID/REFUND）+ outbox，不做网络调用。
2. dispatcher 短事务 claim：`state IN (READY, UNKNOWN_QUERY) AND next_attempt_at <= now()`
   或 `RUNNING AND lease_until < now()`（租约回收），`FOR UPDATE SKIP LOCKED`，
   写入 lease owner/until（30s）后提交；多副本安全。
3. 网关调用在事务外进行，以稳定 providerKey 为幂等键；模拟网关按 providerKey
   持久化结果，重试同 key 返回既有结果（网关侧幂等）。
4. 结果以新事务记录：attempt 行、命令状态、outbox 事件。
5. UNKNOWN（网关成功后本地落库前崩溃等）进入 `UNKNOWN_QUERY` 状态查询循环，
   **不猜测结果**；超过上限进入 MANUAL_REVIEW。人工重试默认复用原 providerKey，
   只有确认远端从未执行才允许新 key（记录批准与因果）。
6. **迟到/额外 capture**：订单已终止时，capture 成功不复活订单——自动创建补偿
   REFUND command（key = `rf-late-<captureProviderKey>`，与 VOID 转退款共用一个 key，
   NOT EXISTS 守卫保证全局唯一），余额行 captured+=amount 后预占并退款。

**退款额度预占**

- `payment_balance(booking_id)` 单行三金额：captured / refund_reserved / refunded，
  行内 CHECK：`refund_reserved + refunded <= captured`。
- 取消接受时在同一事务内原子预占（条件 UPDATE）→ 才允许创建退款 command。
- 退款成功：reserved→refunded，订单 CANCELLATION_PENDING → CANCELLED。
- 退款失败：**预占保留**，指数退避重试，超限转 MANUAL_REVIEW（人工队列可见）；
  只有管理员明确"放弃退款"（记录原因+审计）才释放预占。

**模拟网关隔离**

- 场景仅由服务端配置（provider key 前缀 → SUCCESS/FAILURE/LATE_SUCCESS/
  UNKNOWN_THEN_*/ALWAYS_UNKNOWN），结果持久化在 gateway_results 表。
- prod profile 启动断言：场景规则非空或使用默认密钥 → 拒绝启动。

## 验证

`BookingLifecycleIT`：双 key 单意图；确认后库存/票券/余额；超时释放；
迟到 capture → 补偿退款（金额守恒 refunded == captured）；取消 → 预占 → 成功。
`TicketRedeemIT` 覆盖取消 vs 核销。

## 后果

- 命令路径的所有迁移收敛在 dispatcher + BookingTransitions，可审计、可重放。
- 金额守恒由数据库 CHECK + 条件更新双保险，任何违反都会显式失败并进入人工队列。
