# ADR-003 无间隙 Outbox 序号与有序 relay

状态：已接受

## 背景

Kafka 按 key 分区保序，但应用层重放/恢复/审计需要**每个聚合内无间隙**的序号，
数据库事实不因 relay 故障丢失。

## 决策

- 序号由 `aggregate_counters(aggregate_type, aggregate_id, next_sequence)` 行锁递增分配，
  与业务写入同一事务：**回滚同时回滚 counter，不会留下永久 gap**。
  禁止用 PostgreSQL 原生 sequence 充当聚合序号（有洞）。
- Outbox 唯一约束 `(aggregate_type, aggregate_id, sequence)` 兜底重复分配。
- MVP 使用**单 relay worker**：按 `(aggregate_type, aggregate_id, sequence)` 顺序
  `FOR UPDATE SKIP LOCKED` 取批，逐事件同步 `send().get()` 发布
  （producer：`enable.idempotence=true, acks=all`），发布与标记 PUBLISHED 同事务。
  发布成功但标记前宕机 → 重复发布，属设计内行为，由消费者 cursor 去重。
- 扩展路径（非 MVP）：按 aggregateId 一致性哈希到固定 lane，每 lane 单租约 worker。
- 消费者在同一本地事务中：FOR UPDATE 读 cursor → `seq == last+1` 应用副作用 →
  写 cursor（含 eventId）；`seq <= last` 安全跳过；`seq > last+1` 记录 gap 并
  **只阻塞该聚合**。offset 提交在 DB commit 之后。
- Gap 恢复仅三种（全部审计，支持 dry-run，禁止手改游标）：修复后重放（REPLAY）、
  从可信数据库快照重建游标（REBUILD_CURSOR）、双人批准跳过（SKIP）。
- 反序列化/处理异常经有界重试后进入 `<topic>.DLT`，与 gap 是两类独立异常。

## 验证

`OutboxKafkaIT`：事务回滚无序号洞；relay 按序发布；消费者去重与 gap 记录；poison → DLT。

## 后果

- 事件消费顺序与业务写入顺序强一致，可支撑审计与恢复。
- relay 同步发布的吞吐上限有限（demo 目标内足够）；扩展到 lane 需要新 ADR。
