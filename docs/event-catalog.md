# 事件目录（Kafka · Transactional Outbox）

## Topic

| Topic | 事件 | Key | 说明 |
| --- | --- | --- | --- |
| `catalogue.events.v1` | `event.published` / `event.cancelled` / `tier.inventory_changed` | eventId / tierId | 目录事实变更 |
| `booking.events.v1` | `booking.*` / `payment.*` / `refund.*` / `ticket.*` | bookingId / ticketId | 交易闭环事件 |
| `interaction.events.v1` | `interaction.recorded` | userId 或 sessionId（匿名 hash） | 推荐/漏斗输入 |
| `notification.commands.v1` | `notification.requested` | userId | 通知命令（预留） |

每个 `<topic>.DLT` 承载毒消息（解析失败或有界重试后仍失败的处理）。

## 信封（四组字段）

| 组 | 字段 |
| --- | --- |
| 身份 | `eventId`（UUID）、`eventType`、`schemaVersion`（1） |
| 聚合 | `aggregateType`、`aggregateId`、`aggregateSequence`（无间隙，见 ADR-003） |
| 追踪 | `correlationId`、`causationId`、`traceId` |
| 内容 | `occurredAt`（DB 时钟）、`producer`（`eventpulse-backend`）、`payload` |

`payload` 白名单字段（id、状态、金额 minor、数量、reason 等业务事实）；
**禁止**：票券原文/token、完整位置、密码、幂等键明文、不必要个人资料。

## booking.events.v1 事件清单

| eventType | 触发 | payload 关键字段 | 消费者副作用（notification-consumer） |
| --- | --- | --- | --- |
| `booking.created` | 协议 A 成功 | bookingId, userId, eventId, tierId, quantity | 记录 cursor（分析用） |
| `payment.intent_created` | 单飞意图创建 | bookingId, userId, intentId, providerKey, amountMinor | — |
| `booking.confirmed` | capture 成功 | bookingId, userId, amountMinor | 通知用户 |
| `payment.failed` | capture 失败（仍待支付） | bookingId, userId | 通知用户 |
| `booking.expired` | 超时调度 | bookingId, userId | 通知用户 |
| `booking.cancelled` | 支付前取消 / 无退款取消 | bookingId, userId, phase | 通知用户 |
| `refund.requested` | 退款预占 + REFUND command | bookingId, userId, refundId, amountMinor | — |
| `refund.succeeded` | 退款成功 | bookingId, refundId, amountMinor | 通知用户 |
| `refund.failed` | 退款转人工 | bookingId, refundId, manualReview | 通知用户 |
| `booking.late_capture_compensated` | 迟到 capture 补偿 | bookingId, userId, amountMinor | 通知用户 |
| `booking.cancellation_rejected_used_ticket` | USED 票券拒绝整单取消 | bookingId, usedTickets | 人工队列可见 |
| `ticket.issued` | 确认出票 | bookingId, eventId, sequence | — |
| `ticket.redeemed` | 原子核销 | ticketId, bookingId, organiserId | — |

## 兼容性

- schemaVersion 仅做递增；消费者必须容忍未知字段（payload 白名单允许扩展）。
- 新消费者若历史已过保留期，必须从受版本控制的 snapshot/bootstrap 起点初始化，
  不能假设从序号 1 开始仍可获得。
