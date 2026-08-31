# Runbook（demo 级）

从 trace 定位失败订单/command 的标准路径：

1. 拿到客户端响应里的 `traceId`（每条响应头 `X-Trace-Id`，日志 pattern 中同名 MDC）。
2. `docker compose logs backend | grep <traceId>` → 找到失败步骤与 module 标签。
3. 按场景进入下列 runbook；`/api/v1/admin/exceptions` 是所有队列的统一视图。

## R1 支付 UNKNOWN（不猜测结果）

- 现象：`commands.state = 'UNKNOWN_QUERY'`，意图 `CAPTURE_SUBMITTED` 后无结果。
- 自动行为：dispatcher 按 `unknown-resolve-interval` 调 `queryStatus(providerKey)`；
  结果只有 SUCCESS / FAILURE / 继续 UNKNOWN 三种。
- 超过 max-attempts → `MANUAL_REVIEW`。
- 人工：`/api/v1/admin/exceptions` → 命令"重试"（复用原 providerKey，审计记录）。
  只有确认远端从未执行时才允许新 key（当前未开放 API，需 DBA + 双人批准）。

## R2 迟到 capture / 额外成功

- 现象：`booking.late_capture_compensated` 事件，或 `commands` 里出现 `rf-late-*` REFUND。
- 自动行为：订单不复活，补偿退款自动创建（key 唯一，不会双退），金额守恒
  `refunded == captured`。无需人工介入；在 admin 异常视图可观察。

## R3 退款失败 / 预占卡住

- 现象：`refunds.state = FAILED / MANUAL_REVIEW`，balance.refund_reserved > 0 且 `refund_reserved_age` 持续增长。
- 原则：预占保留，避免另一个命令再次退同一额度。
- 人工选择：
  - 重试：`POST /api/v1/admin/commands/{id}/retry`（复用原 key）。
  - 放弃（明确不再退款）：`POST /api/v1/admin/refunds/{id}/abandon`，记录 reason，
    释放预占并审计。

## R4 Outbox 停滞 / oldest pending 增长

- 诊断：admin exceptions 的 `outboxOldestPendingSeconds`；`docker compose logs backend` 中
  relay 报 `kafka publish failed`。
- 处理：先恢复 Kafka；relay 每 0.5s 自动重试（无消息丢失——DB 是事实源）。
- 手工重放：`POST /api/v1/admin/outbox/replay`（支持 dry-run，幂等，消费者去重）。

## R5 消费者 gap（单聚合阻塞）

- 现象：`consumer_gaps` OPEN，该 aggregate 后续事件不应用（其他聚合不受影响）。
- 人工三选一（全部审计，dry-run 先行）：
  1. REPLAY：重放 outbox 中该聚合 `sequence >= expected` 的事件；
  2. REBUILD_CURSOR：把 cursor 设为该聚合已发布的最大序号（可信快照）；
  3. SKIP：双人批准（approvedBy 必填），cursor 推进到 received，记录数据丢失。
- 禁止直接 UPDATE consumer_cursors（API 才写审计）。

## R6 DLT（毒消息）

- 现象：`<topic>.DLT` 出现消息（解析失败或 3 次重试后仍失败）。
- 处理：修复根因后用 admin outbox replay 或从 outbox 表重放；DLT 双次重放验证由
  `OutboxKafkaIT` 覆盖。

## R7 command lease 卡死

- 现象：`commands.state = 'RUNNING'` 且 `lease_until` 已过（dispatcher 被杀）。
- 自动行为：其他副本（或重启后的本副本）按 `RUNNING AND lease_until < now()` 回收 claim。
- 心跳只能有限续租，不能无限隐藏挂死任务。

## R8 sequence gap 怀疑

- 断言：`aggregate_counters` 与业务同事务分配，回滚无洞（ADR-003）。
- 验证 SQL：对某 aggregate 检查 `outbox.sequence` 是否连续；
  若发现 gap（理论上只可能是手工删行），按 R5 REPLAY/REBUILD 处理。

## 健康检查与指标

- `GET /actuator/health`（liveness/readiness probe）；`/actuator/prometheus` 抓取指标。
- 关键指标：API p95/p99、hikari pool、deadlock 数，以及业务指标
  `eventpulse_outbox_oldest_pending_seconds`、`eventpulse_consumer_lag`、
  `eventpulse_command_lease_age_seconds`、`eventpulse_commands_manual_review`、
  `eventpulse_ticket_redeem_rejections_total / eventpulse_ticket_redeem_attempts_total`、
  `eventpulse_inventory_equation_violations`。这些由 `BusinessMetrics` 从 batch pool
  刷新并经 `/actuator/prometheus` 暴露；可直接导入
  `deploy/observability/grafana-dashboard.json`，Prometheus 规则位于
  `deploy/observability/prometheus-rules.yml`。
