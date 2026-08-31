# ADR-002 两条无环锁协议（协议 A / 协议 B）

状态：已接受

## 决策

**协议 A —— 创建预订**（`BookingService.createBooking`）：

1. 幂等 claim（同事务 INSERT idempotency，冲突即等待首事务提交后裁决）。
2. UPSERT quota 行 → `SELECT ... FOR UPDATE` 锁 quota。
3. `SELECT inventory ... FOR UPDATE` 锁库存行。
4. 条件更新库存（`available >= :q`）→ 条件更新 quota（`active + :q + confirmed <= per_user_limit`）。
5. 插入 booking（PAYMENT_PENDING，expiresAt 由 DB 时钟生成）/ reservation / outbox。

该流程不会等待任何既有 booking 行。

**协议 B —— 既有订单迁移**（`BookingTransitions` 全部入口）：

1. 无锁读 booking 拿到关联 ID（tier/quota 等）。
2. 正式锁定顺序固定：**booking → quota → inventory → reservation → tickets → payment balance**。
3. 锁后重新校验 booking status/version，竞态失败方重读并返回无副作用结果。

容量调整只锁 inventory；活动取消批次按每笔订单独立执行协议 B，
绝不持有 event 行锁等待 booking 锁——因此锁序无回边，无环。死锁仍配置有限重试，
但重试只是兜底，正确性来自固定锁序。

## 验证

- `BookingConcurrencyIT`：100 并发 vs 50 容量；首次 quota 行并发。
- `BookingLifecycleIT`：支付 vs 超时、迟到 capture、取消退款。
- 数据库 deadlock 指标（micrometer）在 dashboard 中可见。

## 后果

- 所有状态迁移代码必须经过 `BookingTransitions`，禁止旁路写 bookings/inventory。
- 违反锁序的代码在并发测试中会以死锁/不一致暴露（测试即护栏）。
