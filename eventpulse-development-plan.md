# EventPulse 开发计划

> 本文由 `eventpulse-detailed-project-plan.md`（技术架构与分层基线）与 `eventpulse-crud-enhancement-plan.md`（运营闭环与 CRUD 丰富）合并而成，是唯一的开发计划来源。原两份文档已被本文取代，历史版本可在 git 中查阅。
>
> Kaiwen Yao · 合并日期 2026-09-01 · AI 方向修订 2026-09-02

Spring Boot 4 · Kafka · PostgreSQL（PostGIS）· Redis · Python AI Service（FastAPI + LangChain）· 外部 LLM。分层对齐 firmament：`Controller → Service → Repository`。统一 `Result` 信封，JWT 拦截器鉴权。AI 作为被业务调用的独立服务：主办方用它完善活动文案，用户用它通过受控工具查找真实活动。

本项目不训练模型、不做微调、不生成 embedding、不使用 pgvector，也不建设机器学习排序流水线。LLM 不能直接执行任意 SQL，不能自动发布活动，不能修改库存、订单或票据。

## 目录

1. [目标、成功标准与边界](#1-目标成功标准与边界)
2. [目标架构（对照架构图）](#2-目标架构对照架构图)
3. [角色、权限与页面](#3-角色权限与页面)
4. [分层与模块](#4-分层与模块)
5. [数据模型](#5-数据模型)
6. [鉴权](#6-鉴权)
7. [统一响应与错误码](#7-统一响应与错误码)
8. [活动生命周期与 CRUD 规则](#8-活动生命周期与-crud-规则)
9. [HTTP API（完整清单）](#9-http-api完整清单)
10. [票据与核销](#10-票据与核销)
11. [Kafka 与 Outbox](#11-kafka-与-outbox)
12. [AI 服务 / LangChain Agent](#12-ai-服务--langchain-agent)
13. [运营分析与可观测性](#13-运营分析与可观测性)
14. [前端](#14-前端)
15. [实施路线](#15-实施路线)
16. [测试与 CI](#16-测试与-ci)
17. [最终版本完成定义](#17-最终版本完成定义)
18. [诚实边界](#18-诚实边界)
19. [附录 A：现状对照](#附录-a现状对照2026-09-01-抽查)

---

## 1 目标、成功标准与边界

### 1.1 项目定位

EventPulse 用最少的代码同时讲清两件事：

- **技术主线**：HTTP CRUD、JWT 鉴权、独占库存、Outbox 可靠消息、SSE 实时、LLM 工具调用。
- **业务主线**：一条完整的主办方到参与者运营闭环。

```text
创建草稿 → 编辑预览 → 发布售票 → 管理订单与参与者 → 逐票签到 → 结束归档
```

两条主线缺一不可：只有技术盒子跑通、业务闭环走不完，不算完成；只有页面按钮齐全、并发与消息不可靠，同样不算完成。

### 1.2 技术主线的六件事

1. **HTTP CRUD**：注册登录、活动目录、活动管理、预订、票据。
2. **鉴权**：JWT 拦截器，不用 Spring Security 过滤器链；主办方接口强制所有权校验。
3. **库存**：`Inventory` 独占 `sold` / 余票，预订只调它，靠数据库条件更新防超卖。
4. **消息**：同一事务写 Outbox，relay 发 Kafka，消费者写通知，按 `dedup_key` 幂等。
5. **实时**：预订与票据状态用 SSE 推，REST 仍是事实源。
6. **AI 助手**：Spring Boot 调用独立 Python AI 服务；文案助手返回可审核草稿，活动发现 Agent 通过受控工具查询真实数据。

### 1.3 成功门槛

| 维度 | 门槛 | 证据 |
| --- | --- | --- |
| CRUD + 鉴权 | `make up` 能注册、浏览、预订、取消；无 token 打需登录接口返回 401；非所有者打主办方接口返回 403 | smoke + 拦截器单测 |
| 活动生命周期 | 六态齐全，非法状态转换被拒绝；草稿可删、有订单只能取消 | Service 单测 |
| 库存 | 余票不足拒绝；取消把 `sold` 减回；只有 Inventory 改 `sold`；并发预订不超卖 | Service 单测 + 并发测试 |
| 编辑并发 | 版本冲突返回 409，主办方被要求刷新重编 | Service 单测 |
| 票据核销 | 每张票独立二维码；重复核销被拒；撤销核销可恢复 | Service 单测 + E2E |
| 消息 | 预订 / 取消后消息中心出现通知；杀进程重放 Outbox 仍能发出；重复消费不产生重复通知 | smoke + Outbox 单测 |
| 实时 | 已打开的预订 SSE 在状态变化后收到 `booking-status` | 单测 / 手工 |
| AI 文案 | 能根据活动草稿生成标题、摘要、详细描述和入场须知；主办方确认后才能写入 | 固定示例评测 + API 集成测试 |
| AI 找活动 | Agent 正确选择查询工具，只推荐数据库实际返回且仍公开的活动 | 工具调用测试 + “不编造活动”测试 |
| 运营分析 | 主办方能看到浏览、点击、预订、售票率与转化漏斗 | 分析接口测试 + 看板页 |
| 质量 | 后端 JaCoCo 行覆盖率 ≥ 90%；前端 Vitest ≥ 80%；CI 在 `main` 全绿 | `mvn verify`、Vitest、`uv run pytest` |

### 1.4 非目标

- 真实收单、钱包、自动退款、发票与财务结算。
- 复杂票种、座位图、动态定价。
- 多级组织、团队成员、精细化企业权限。
- 分布式锁协议、完整 Saga 编排器、生产级运维。
- 训练模型、微调模型、embedding、向量数据库和机器学习推荐排序。
- 允许 AI 绕过 Spring Boot 权限直接读写数据库。
- 让 AI 自动发布活动、下单、退款、核销或修改库存。

活动取消后如果已存在订单，系统只记录取消状态并通知参与者；退款标记为线下处理，不宣称已自动退款。

---

## 2 目标架构（对照架构图）

```text
React Discovery App / Organiser Console
        │  REST + SSE
        ▼
Spring Boot ─ Catalogue · Booking · Inventory · Ticketing · Notification · Analytics · AI Gateway
        │                         │
        │                         └── HTTP ──> Python AI Service ──> LLM API
        │                                        │
        │                                        └── 受控工具 ──> Spring Boot 内部查询接口
        ▼
Kafka · PostgreSQL + PostGIS · Redis · Outbox · Metrics
```

| 图中盒子 | 计划落地 | 对照本文件 |
| --- | --- | --- |
| React Discovery App | `/` 发现、详情、推荐、附近、收藏、预订、票据、消息、偏好 | §3、§14 |
| Organiser Console | `/organiser` 工作台、活动管理、详情、编辑、参与者、分析 | §3、§14 |
| REST | 全部 JSON API，包在 `Result` | §9 |
| SSE | `GET /api/bookings/{id}/events`，状态变化推 `booking-status` | §9.11 |
| Catalogue | 活动搜索、详情、附近（PostGIS）、主办方写活动 | §4.2 |
| Inventory | 独占 `capacity` / `sold`；预订不得直接改 `events.sold` | §4.3 |
| Booking | 创建 / 查询 / 取消；调 Inventory；写 Outbox | §9.4 |
| Ticketing | 按数量生成独立票据、二维码、逐票核销与撤销 | §10 |
| AI Gateway | 鉴权、限流、调用 Python AI 服务、校验返回结果 | §9.8、§12 |
| Python AI Service | FastAPI + LangChain；文案生成与活动发现 Agent | §12 |
| LLM API | 外部大语言模型，只在运行时调用，不在项目中训练 | §12 |
| Notification | Kafka 消费后写 `notifications`，供消息中心查询 | §9.6、§11 |
| Media | 受控图片上传、封面引用与生命周期 | §9.9 |
| Analytics | 活动漏斗、日聚合、主办方看板 | §13 |
| Kafka | topic `booking-events` | §11 |
| PostgreSQL + PostGIS | 保存业务数据；附近活动按经纬度查询 | §5、§9.2 |
| Redis | 热门活动 / 热门计数缓存，TTL 60s | §4.5 |
| Outbox | 与业务同一事务插入 `outbox`；`OutboxRelay` 再发 Kafka（教学版，不做 gap/DLT） | §11 |
| Metrics Dashboard | Actuator Prometheus + `/api/meta/metrics` + 前端看板页 | §13 |

图里没有、也不做的：钱包、真实网关、锁协议 A/B、完整 Saga 状态机。Outbox 承担「写库与发消息同命运」；预订事务本身是单库本地事务，不跨服务编排。

---

## 3 角色、权限与页面

### 3.1 角色

| 角色 | 可执行操作 |
| --- | --- |
| 访客 | 浏览、搜索、筛选活动，查看活动详情，使用公开的 AI 活动发现助手 |
| `USER` | 访客能力，以及收藏、预订、取消自己的预订、查看订单与票据、消息中心、偏好、使用个性化 AI 活动发现助手 |
| `ORGANISER` | 创建和管理自己的活动，查看自己活动的订单、参与者、核销与运营数据，使用 AI 完善活动文案 |
| AI 助手 | 根据当前用户权限调用受控工具；只生成建议，不直接修改业务事实 |

注册接口不接受 `role`，一律写成 `USER`。演示主办方由 `demo` profile 播种。

### 3.2 主办方鉴权四条件

权限必须在后端强制校验，前端路由保护只是体验优化。主办方操作活动时必须同时满足：

1. 当前用户已登录。
2. 当前用户角色为 `ORGANISER`。
3. 目标活动的 `organiserId` 等于当前用户 ID。
4. 当前活动状态允许执行该操作。

所有查询都在 Repository 或 SQL 层带上 organiser 所有权约束，Service 层再次校验。

### 3.3 普通用户主流程

1. 注册 / 登录，拿到 JWT。
2. （可选）写入兴趣类别、常驻城市、可选坐标。
3. Discovery 浏览列表、附近活动，或用自然语言让 AI 助手查找合适的活动；点进详情上报 `VIEW` / `CLICK`。
4. 预订：Booking → Inventory 扣减 → 生成票据 → 同事务写 Outbox → relay 发 Kafka → 通知 + `BOOK` 互动；SSE 推 `CONFIRMED`。
5. 取消同理，发 `BOOKING_CANCELLED`、记 `CANCEL`、对应票据失效。

### 3.4 前端路由

| 路径 | 表面 | 页面 | 鉴权 |
| --- | --- | --- | --- |
| `/` | Discovery | 搜索 + 筛选 + 附近 + 列表 + AI 活动助手入口 | 公开 |
| `/events/:id` | Discovery | 活动详情 / 收藏 / 预订 | 浏览公开，预订需登录 |
| `/login` | Discovery | 登录 / 注册 | 公开 |
| `/preferences` | Discovery | 兴趣类别、城市、坐标 | 登录 |
| `/favourites` | Discovery | 我的收藏 | 登录 |
| `/bookings` | Discovery | 我的订单，按即将开始 / 已结束 / 已取消分组 | 登录 |
| `/bookings/:id` | Discovery | 订单详情、逐票二维码、连 SSE | 登录 |
| `/notifications` | Discovery | 消息中心 | 登录 |
| `/profile` | Discovery | 个人中心：余额、累计消费、账户统计、演示充值 | 登录 |
| `/organiser` | Organiser Console | 工作台：概览、近期活动、待处理事项 | 主办方 |
| `/organiser/events` | Organiser Console | 活动管理：搜索、状态筛选、分页 | 主办方 |
| `/organiser/events/new` | Organiser Console | 新建活动，存草稿或直接发布 | 主办方 |
| `/organiser/events/:id` | Organiser Console | 活动运营详情、生命周期轨道、快捷操作 | 主办方 |
| `/organiser/events/:id/edit` | Organiser Console | 编辑活动 | 主办方 |
| `/organiser/events/:id/attendees` | Organiser Console | 订单、参与者、核销、导出 | 主办方 |
| `/organiser/analytics` | Organiser Console | 浏览、点击、预订、售票率、转化率、漏斗 | 主办方 |

前端路由规则：未登录访问 `/organiser/**` 跳登录页；普通用户访问展示无权限页；主办方页面请求失败必须展示明确错误，不能静默为空列表。

### 3.5 演示账号（`demo` profile）

| 角色 | 邮箱 | 密码 |
| --- | --- | --- |
| USER | `user@eventpulse.dev` | `User123456` |
| ORGANISER | `organiser@eventpulse.dev` | `Organiser123456` |

---

## 4 分层与模块

```text
Controller  → REST / SSE；返回 Result<T>；不写业务规则
Service     → 业务模块（见 §4.2）
Repository  → Spring Data JPA / JdbcTemplate（普通查询与 PostGIS）
Entity      → 表行；不直接作为 HTTP 响应
dto         → *Request 入参，*Vo 出参（Java record，不单独建 vo 包）
domain      → 状态常量与状态机（EventStatus、TicketStatus 等）
Interceptor → JWT；公开路径白名单；可选 token 的接口要解析但不强制
outbox      → 与业务同一事务写入；OutboxRelay 轮询发 Kafka
kafka       → Consumer 写 notifications 和 BOOK/CANCEL 互动
redis       → 热门缓存
ai-service/ → FastAPI + LangChain；调用外部 LLM，并通过受控工具读取业务信息
```

### 4.1 文件组织

高内聚低耦合，按领域而不是按类型组织；单文件 200–400 行为常态，800 行为上限；函数控制在 50 行内，嵌套不超过 4 层。

### 4.2 业务模块

| 模块 | 职责 | 禁止 |
| --- | --- | --- |
| Catalogue | 列表、详情、附近、主办方活动 CRUD 与生命周期 | 改 `sold`；执行 AI 工具编排 |
| Inventory | `reserve(eventId, qty)` / `release(eventId, qty)`；读 `remaining` | 发 Kafka；改订单状态 |
| Booking | 创建 / 取消 / 查询；调 Inventory 与 Ticketing；写 Outbox | 直接 `event.setSold` |
| Ticketing | 生成票据、核销、撤销核销、票据状态汇总 | 改 `sold`；改订单数量 |
| AI Gateway | 调用 Python AI 服务、传递用户上下文、限流、超时和返回校验 | 让 AI 绕过权限或直接写业务表 |
| AI Service | 文案生成、活动发现 Agent、工具选择和结构化输出 | 直接访问任意 SQL；自动发布、下单或核销 |
| Notification | 消费 Kafka、落 `notifications`、供消息中心查询 | 改预订 |
| Media | 图片上传、类型与大小校验、所有权校验、软删除 | 越权删除他人资源 |
| Analytics | 漏斗、日聚合、主办方看板 | 写业务事实表 |

VO 放在 `dto` 包里。Entity 不出接口：`User.password` 不得出现在 `UserVo`；`EventVo.remaining` 来自 Inventory，不是列。参与者列表只返回运营需要的信息，不返回密码、JWT 或其他敏感字段。

### 4.3 Inventory 与并发

预订成功：

```sql
UPDATE events
SET sold = sold + :quantity,
    updated_at = now()
WHERE id = :eventId
  AND status = 'PUBLISHED'
  AND sold + :quantity <= capacity;
```

更新行数为 0 时，按活动状态和库存分别返回「活动不可预订」或「余票不足」。取消：`sold = GREATEST(sold - qty, 0)`。并发下只靠这一条条件更新，不引入显式锁协议。

### 4.4 乐观锁

活动编辑使用 `events.version`：

- 读取活动时返回版本号。
- `PUT` 更新时提交旧版本号。
- 版本不匹配返回 `409`，提示主办方刷新后重新编辑。

### 4.5 Redis

| key | 值 | TTL |
| --- | --- | --- |
| `popular:events` | 热门 `EventVo[]` JSON | 60s |
| `popular:counts` | eventId → BOOK+CLICK 计数 | 60s |

Redis 挂了必须回源 PostgreSQL，推荐和列表不能 500。Compose 加 `redis:8`，端口 6379。

---

## 5 数据模型

业务表见 `backend/src/main/resources/db/migration/`。Flyway 只加列 / 加表，不改已有列含义。PostgreSQL 的地理位置查询可以使用 **PostGIS**；AI 功能不要求任何向量扩展。

### 5.1 核心业务表（V1）

| 表 | 关键字段 |
| --- | --- |
| users | email UNIQUE, password (BCrypt), name, role (`USER` / `ORGANISER`), **wallet_cents**（站内钱包余额，分） |
| events | title, description, category, city, starts_at, price_cents, capacity, sold, organiser_id, status, created_at, **location geography(Point,4326)** |
| bookings | user_id, event_id, quantity, status (`CONFIRMED` / `CANCELLED`), created_at |
| notifications | booking_id, message, created_at |

类别：`music` / `tech` / `sports` / `art` / `food`。`location` 可空，有坐标才进入附近活动查询。

### 5.2 运营字段扩展（V2）

`events` 新增：

| 字段 | 用途 |
| --- | --- |
| `summary` | 活动简短摘要 |
| `venue_name` / `address` / `latitude` / `longitude` | 场地与坐标 |
| `ends_at` | 结束时间 |
| `cover_url` / `cover_asset_id` | 封面图与媒体引用 |
| `sales_start_at` / `sales_end_at` | 售票时间窗 |
| `max_quantity_per_booking` | 单次最大预订数量 |
| `contact_info` / `attendance_notes` | 联系方式与入场须知 |
| `cancellation_reason` / `cancelled_at` | 取消原因与时间 |
| `updated_at` / `archived_at` / `archive_note` | 更新、归档时间与归档备注 |
| `version` | 乐观锁版本号 |

`bookings` 新增 `cancelled_at`、`organiser_note`。签到状态由关联 `tickets` 聚合得出，不在 booking 上重复保存签到事实。

`notifications` 从仅关联 booking 扩展为用户消息中心：`user_id`、`event_id`、`booking_id`（可空）、`type`、`title`、`message`、`payload`、`dedup_key`、`read_at`、`created_at`。

### 5.3 新增表

| 表 | 关键字段 | 用途 |
| --- | --- | --- |
| event_audit_logs | event_id, operator_id, action, before_data, after_data, created_at | 发布、重要修改、取消、归档的审计 |
| event_favourites | user_id, event_id, created_at, UNIQUE(user_id, event_id) | 收藏 |
| tickets | booking_id, event_id, ticket_code_hash UNIQUE, status, checked_in_at, checked_in_by, check_in_source, revoked_at, revoked_by, revocation_reason, created_at | 逐张票据 |
| media_assets | owner_id, storage_key, public_url, content_type, size_bytes, status, created_at, deleted_at | 封面与媒体生命周期 |
| user_preferences | user_id PK, categories `TEXT[]`, city, lat, lng | 显式兴趣；坐标供 nearby |
| interactions | user_id NULL, event_id, type, position, occurred_at | point-in-time 行为 |
| ai_conversations | id, user_id NULL, kind, created_at, updated_at | 可选保存 AI 活动助手的多轮会话 |
| ai_messages | id, conversation_id, role, content, created_at | 保存有限长度的会话历史，不保存模型内部思考过程 |
| ai_requests | request_id, user_id NULL, feature, provider, model_name, status, latency_ms, input_tokens, output_tokens, created_at | 记录调用结果、耗时和成本，不保存密钥 |
| outbox | topic, payload JSON, created_at, published_at | 与业务同事务；relay 发出后填 published_at |
| event_daily_metrics | event_id, day, views, clicks, saves, bookings, sold, cancels, check_ins | 工作台与趋势查询 |

`ticket_code_hash` 保存核销码的哈希值，不保存可直接入场的原始密钥。票据状态：`VALID` / `CHECKED_IN` / `CANCELLED` / `REVOKED`。

`interactions.type`：`VIEW` / `CLICK` / `SAVE` / `UNSAVE` / `BOOK` / `CANCEL`。客户端只能提交 `VIEW` / `CLICK` / `SAVE` / `UNSAVE`；`BOOK` / `CANCEL` 只由 Kafka 消费者写。

`event_daily_metrics` 是聚合表，原始事件仍是事实来源，必须支持重新计算。

### 5.4 不变量

- AI 工具不得修改 `sold`、`bookings.status` 或票据状态。
- AI 推荐的活动 ID 必须来自本次工具查询结果。
- AI 生成的活动文案必须由主办方确认后，才能通过普通活动编辑接口保存。
- 容量不能小于已售票数。
- 只有 Inventory 改 `events.sold`。

---

## 6 鉴权

`JwtService` 签发 HS256，claims：`userId`、`role`。Header：`Authorization: Bearer <token>`。SSE 同样走 Header，**token 不得放进 query**。

`JwtInterceptor` 拦截 `/api/**`。公开路径：

- 所有 `OPTIONS`
- `POST /api/auth/login`、`POST /api/auth/register`
- `GET /api/events`、`GET /api/events/{数字id}`、`GET /api/events/nearby`
- `POST /api/ai/discovery/chat`（可选 JWT）
- `GET /actuator/health`、`GET /actuator/prometheus`

`POST /api/ai/discovery/chat` 可选 JWT：未登录可以按公开条件找活动；已登录时可以在用户明确同意后读取其偏好。公开路径如果带 `Bearer`，仍然要解析用户身份。

其余无 token → HTTP 401，`{"code":0,"msg":"未登录或 token 无效"}`。

主办方接口与 `/api/meta/metrics`：Service 校验 `role == ORGANISER` 且资源属于当前用户。注册 DTO 不接受 `role` / `status` / `organiserId`；活动表单不接受 `organiserId` / `sold` 等服务端维护字段。

### 6.1 数据安全

- DTO 不返回密码。
- 日志不打印 JWT、LLM API Key、完整用户提示词、完整模型回复和参与者敏感信息。
- 导出参与者数据前再次验证活动所有权。
- 媒体上传校验类型、大小与资源所有权；被活动引用的媒体不能被他人删除。

---

## 7 统一响应与错误码

成功 HTTP 200：`{"code":1,"msg":null,"data":{}}`。无 body 时 `data` 为 `null`。
错误：`{"code":0,"msg":"余票不足","data":null}`，HTTP 状态按语义区分，不再把所有业务异常统一成 400。

| 状态码 | 场景 |
| --- | --- |
| `400` | 参数格式或业务输入错误（校验失败取第一个字段错误） |
| `401` | 未登录或 token 无效 |
| `403` | 角色无权访问或不是资源所有者 |
| `404` | 活动、订单、票据不存在 |
| `409` | 状态冲突、版本冲突、库存冲突、重复核销 |
| `500` | 未处理的服务器错误 |

`BusinessException` 携带 HTTP 状态，由 `GlobalExceptionHandler` 统一转换。

SSE 不是 `Result`：`Content-Type: text/event-stream`，事件名为 `booking-status` / `heartbeat`。

时间 ISO-8601 UTC。金额整数分 `priceCents`。

---

## 8 活动生命周期与 CRUD 规则

### 8.1 状态

| 状态 | 含义 | 是否公开展示 | 是否允许预订 |
| --- | --- | --- | --- |
| `DRAFT` | 草稿 | 否 | 否 |
| `PUBLISHED` | 已发布 / 售票中 | 是 | 满足售票时间和库存时允许 |
| `ONGOING` | 活动进行中 | 是 | 否 |
| `FINISHED` | 已结束 | 可查看 | 否 |
| `CANCELLED` | 已取消 | 原链接可查看 | 否 |
| `ARCHIVED` | 已归档 | 默认不进入公开列表 | 否 |

### 8.2 允许的状态转换

```text
DRAFT     → PUBLISHED
DRAFT     → 删除
PUBLISHED → CANCELLED
PUBLISHED → ONGOING
ONGOING   → FINISHED
FINISHED  → ARCHIVED
CANCELLED → ARCHIVED
```

不允许把 `CANCELLED` 或 `FINISHED` 恢复为 `PUBLISHED`。需要重复举办就用「复制活动」创建新活动。

### 8.3 创建

活动表单字段分四组：

- **基本信息**：标题、简短摘要、详细介绍、分类、封面图。
- **时间地点**：开始时间、结束时间、城市、场地名称、详细地址、可选经纬度。
- **售票设置**：票价 `priceCents`、总容量、售票开始时间、售票截止时间、单次最多预订数量。
- **参与说明**：联系方式、入场须知、取消说明。

创建操作支持保存草稿、发布前预览、直接发布、从已有活动复制、字段校验、离开前未保存提醒。

业务规则：新活动默认 `DRAFT`，除非明确点「发布活动」；开始时间早于结束时间；售票截止不晚于活动开始；容量 ≥ 1；票价 ≥ 0；坐标要么都空，要么成对出现（纬度 [-90,90]，经度 [-180,180]）。

### 8.4 查询

主办方活动列表支持：标题关键词搜索、状态筛选、分类筛选、开始日期区间、按最近更新 / 开始时间 / 售票量排序、数据库分页；展示状态、时间、城市、已售、容量、余票与售票进度；每行提供与状态匹配的操作（查看、编辑、预览、复制、发布、取消、归档、删除草稿）。

主办方活动详情至少展示：完整资料、当前生命周期状态、已售 / 余票 / 售票率、订单数与取消数、最近订单或操作记录、可执行操作及不能操作的原因。

### 8.5 修改

| 活动状态 | 允许修改 |
| --- | --- |
| `DRAFT` | 所有可编辑字段 |
| `PUBLISHED` | 文案、封面、地点、参与须知、未来时间、容量等受控字段 |
| `ONGOING` | 联系方式、参与须知等非交易字段 |
| `FINISHED` | 原则上只读，可补充归档备注 |
| `CANCELLED` | 只读，可查看取消原因和订单影响 |
| `ARCHIVED` | 只读 |

通用限制：容量不得小于已售票数；修改时间或地点且已有订单时，向主办方展示影响人数并通知参与者；`PUT` 带版本号做乐观锁；重要修改写 `event_audit_logs`；坐标变更时更新 `location`。AI 生成的文案仍然通过相同的 `PUT` 接口和业务校验保存。

### 8.6 删除、取消与归档

| 条件 | 处理方式 |
| --- | --- |
| 草稿且没有订单 | 允许永久删除 |
| 已发布且没有订单 | 允许取消 |
| 已存在订单 | 禁止物理删除，只能取消 |
| 已结束 | 允许归档 |
| 已取消 | 保留记录，可归档，不物理删除 |

取消活动的完整流程：

1. 要求填写取消原因。
2. 展示受影响订单数和参与者人数。
3. 活动状态置为 `CANCELLED`，写 `cancelled_at` 与 `cancellation_reason`。
4. 停止新的预订。
5. 保留活动和订单记录，对应票据立即失效。
6. 为已有参与者生成站内通知（走 Outbox）。
7. 记录操作者、时间和原因到审计表。

`cancel` 和 `archive` 是明确的业务动作，不用 `DELETE` 混合表达；`DELETE` 只用于允许物理删除的草稿。

---

## 9 HTTP API（完整清单）

基址：`http://localhost:8080`。Compose 前端 `http://localhost:3000`，`/api` 反代到 backend。

主办方管理能力收敛到 `/api/organiser` 命名空间；公开目录留在 `/api/events`。旧接口在迁移期保留兼容。

### 9.1 认证 `/api/auth`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | 公开 | `{email, password, name}`；email 合法唯一、password 6–64、name ≤ 50；role 固定 `USER`；邮箱重复 → `"邮箱已被注册"` |
| POST | `/api/auth/login` | 公开 | `{email, password}`；失败 → `"邮箱或密码错误"` |
| GET | `/api/auth/me` | JWT | `{id, email, name, role}`，不含 password / token |
| GET | `/api/auth/profile` | JWT | `ProfileVo`：`{id, email, name, role, walletCents, totalSpentCents, bookingCount, ticketCount, favouriteCount, notificationCount}` |
| POST | `/api/auth/wallet/recharge` | JWT | `{amountCents}`（100–500000，即 ¥1–¥5000）；模拟充值并增加站内余额，不做真实支付；返回 `ProfileVo` |

register / login 成功返回：

```json
{
  "token": "<jwt>",
  "user": { "id": 1, "email": "a@b.com", "name": "Kaiwen", "role": "USER" }
}
```

### 9.2 公开目录 `/api/events`

`EventVo`：

```json
{
  "id": 1,
  "title": "城市脉搏 · 独立摇滚之夜",
  "summary": "一晚三支乐队",
  "description": "演示活动，可直接预订。",
  "category": "music",
  "city": "上海",
  "venueName": "Livehouse A",
  "address": "某路 1 号",
  "lat": 31.23,
  "lng": 121.47,
  "startsAt": "2026-09-14T12:00:00Z",
  "endsAt": "2026-09-14T15:00:00Z",
  "coverUrl": "/media/1.jpg",
  "priceCents": 18000,
  "capacity": 300,
  "sold": 2,
  "remaining": 298,
  "salesStartAt": null,
  "salesEndAt": "2026-09-14T10:00:00Z",
  "maxQuantityPerBooking": 10,
  "organiserId": 2,
  "status": "PUBLISHED",
  "version": 3
}
```

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/events` | 公开 | 关键词 `q`、`city`、`category`、日期区间、价格、余票筛选；按时间 / 热度 / 价格排序；数据库分页。只返回公开状态（`PUBLISHED` / `ONGOING` / `FINISHED`），已取消不进默认发现列表 |
| GET | `/api/events/nearby` | 公开 | `lat`、`lng`、`radiusKm`（默认 10，上限 50）；PostGIS 按距离升序；无坐标的活动不进结果 |
| GET | `/api/events/{id}` | 公开 | `id` 必须是数字；不存在 → 404 `"活动不存在"`；已取消仍可按 id 看，并展示取消原因 |
| POST | `/api/events/{id}/favourite` | JWT | 收藏 |
| DELETE | `/api/events/{id}/favourite` | JWT | 取消收藏 |
| GET | `/api/favourites` | JWT | 我的收藏 |

### 9.3 主办方活动管理 `/api/organiser/events`

```http
GET    /api/organiser/events
POST   /api/organiser/events
GET    /api/organiser/events/{id}
PUT    /api/organiser/events/{id}
DELETE /api/organiser/events/{id}

POST   /api/organiser/events/{id}/publish
POST   /api/organiser/events/{id}/cancel
POST   /api/organiser/events/{id}/archive
POST   /api/organiser/events/{id}/duplicate
```

列表参数示例：

```http
GET /api/organiser/events?q=音乐&status=PUBLISHED&category=music&page=0&size=20&sort=updatedAt,desc
```

`PUT` 请求携带 `version` 做乐观锁；`DELETE` 只对无订单草稿生效；`cancel` 需要 `reason`；`archive` 可带 `archiveNote`。

### 9.4 订单与票据 `/api/bookings`

`BookingVo`：

```json
{
  "id": 10,
  "eventId": 1,
  "eventTitle": "城市脉搏 · 独立摇滚之夜",
  "quantity": 2,
  "status": "CONFIRMED",
  "ticketsTotal": 2,
  "ticketsCheckedIn": 0,
  "createdAt": "2026-08-31T12:00:00Z",
  "cancelledAt": null
}
```

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/bookings` | JWT | `{eventId, quantity}`，`quantity` 1–`maxQuantityPerBooking`。同一事务：Inventory.reserve → 插 `bookings` → 生成 `tickets` → 插 `outbox` |
| GET | `/api/bookings` | JWT | 当前用户全部订单，按 `createdAt` 降序，可按即将开始 / 已结束 / 已取消分组 |
| GET | `/api/bookings/{id}` | JWT | 只能看自己的；否则 404 / 403 |
| POST | `/api/bookings/{id}/cancel` | JWT | 仅 `CONFIRMED`。同一事务：Inventory.release → `CANCELLED` → 票据失效 → Outbox `BOOKING_CANCELLED` |
| GET | `/api/bookings/{id}/tickets` | JWT | 逐张票据与二维码、当前核销状态 |

预订失败语义：

| 条件 | HTTP | msg |
| --- | --- | --- |
| 活动不存在 | 404 | 活动不存在 |
| 活动未发布 / 已取消 / 已开始 | 409 | 活动当前不可预订 |
| 售票未开始或已截止 | 409 | 不在售票时间内 |
| reserve 影响 0 行 | 409 | 余票不足 |
| 未登录 | 401 | 未登录或 token 无效 |

不做支付、不做幂等键；同一用户可多次预订同一活动。

### 9.5 主办方订单、参与者与核销

```http
GET  /api/organiser/events/{id}/bookings
GET  /api/organiser/events/{id}/attendees
GET  /api/organiser/events/{id}/attendees.csv
POST /api/organiser/tickets/check-in
POST /api/organiser/tickets/{id}/undo-check-in
GET  /api/organiser/tickets/{code}
```

支持按订单号、参与者姓名或邮箱搜索，按订单状态和是否签到筛选，按创建时间排序分页，并返回总订单数、总票数、取消数、签到数。

### 9.6 消息中心 `/api/notifications`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/notifications` | JWT | `{id, type, title, message, eventId, bookingId, payload, readAt, createdAt}` |
| POST | `/api/notifications/{id}/read` | JWT | 标记已读 |

消息类型至少覆盖：预订成功、预订取消、活动时间或地点变更、活动取消、活动即将开始、票据核销。

前端名称是「消息中心」，Kafka 是内部实现，不暴露给最终用户。

### 9.7 偏好与互动

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/preferences` | JWT | 无记录不 404：`{"categories": [], "city": null, "lat": null, "lng": null}` |
| PUT | `/api/preferences` | JWT | `{categories, city, lat, lng}`；categories 为约定子集，可空；lat/lng 成对可选 |
| POST | `/api/interactions` | JWT | 批量上报，1–50 条，`type` 仅 `VIEW` / `CLICK` / `SAVE` / `UNSAVE`，可带 `requestId` 与 `position`；返回 `{"accepted": n}`；未知 `eventId` 忽略；禁止客户端传 `BOOK` / `CANCEL` |

### 9.8 AI 助手 `/api/ai`

浏览器只调用 Spring Boot。Spring Boot 负责 JWT、角色和资源所有权校验，然后通过内部网络调用 Python AI 服务。浏览器不能直接访问 Python 服务，也不能拿到 LLM API Key。

#### 主办方完善活动文案

```http
POST /api/ai/organiser/improve-event
Authorization: Bearer <jwt>
```

请求可以来自尚未保存的表单，也可以携带主办方自己的活动 ID：

```json
{
  "eventId": 12,
  "title": "周末爵士夜",
  "summary": "爵士演出",
  "description": "三支乐队",
  "category": "music",
  "city": "上海",
  "venueName": "声空间",
  "audience": "喜欢现场音乐的年轻人",
  "tone": "轻松、有现场感"
}
```

AI 服务返回固定结构，不直接保存活动：

```json
{
  "requestId": "01J...",
  "suggestion": {
    "title": "周末爵士夜：在城市里听见即兴",
    "summary": "三支爵士乐队带来一晚现场演出。",
    "description": "...",
    "attendanceNotes": "建议提前 30 分钟到场。"
  },
  "warnings": []
}
```

主办方在页面中逐项查看差异，可以选择“应用全部”或只应用某几个字段。应用建议只改变前端表单；最终保存和发布仍然调用普通活动接口，并再次经过版本、权限和状态校验。

#### 用户用自然语言找活动

```http
POST /api/ai/discovery/chat
Authorization: Bearer <optional-jwt>
```

请求：

```json
{
  "conversationId": null,
  "message": "帮我找这个周六在上海、预算 200 元以内的音乐活动"
}
```

返回：

```json
{
  "requestId": "01J...",
  "conversationId": "01J...",
  "answer": "我找到两场符合时间、地点和预算的活动。",
  "events": [
    {
      "eventId": 1,
      "reason": "周六晚开始，位于上海，票价 180 元"
    }
  ],
  "followUpQuestions": []
}
```

`events[].eventId` 必须来自 Agent 本次调用活动搜索工具得到的结果。Spring Boot 收到 Python 返回值后，还要重新读取这些活动，过滤已经取消、结束、下架或无权限展示的数据，再返回完整 `EventVo`。

原有 `GET /api/recommendations` 不再规划为机器学习接口。迁移期间可以保留为普通热门活动列表，前端新的 AI 入口使用 `/api/ai/discovery/chat`。

### 9.9 媒体 `/api/media/images`

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/media/images` | JWT | 上传封面图，校验 content-type 与大小，返回 `{id, publicUrl}` |
| GET | `/api/media/images/{id}` | 公开 | 读取 |
| DELETE | `/api/media/images/{id}` | JWT | 软删除，只有所有者可删，被活动引用时拒绝 |

### 9.10 分析与看板

```http
GET /api/organiser/dashboard
GET /api/organiser/events/{id}/analytics
GET /api/organiser/analytics?from=2026-09-01&to=2026-09-30
GET /api/meta/metrics
```

`/api/organiser/dashboard` 返回近期活动、待处理事项、低余票与临近活动提醒。
`/api/organiser/events/{id}/analytics` 返回该活动 views / clicks / saves / bookings / sold / cancels / checkIns 与转化漏斗。
`/api/meta/metrics`：

```json
{
  "bookingsCreated": 12,
  "bookingsCancelled": 2,
  "recommendationsServed": 40,
  "outboxPending": 0
}
```

### 9.11 SSE

`GET /api/bookings/{id}/events`，JWT，`text/event-stream`。仅订单所有者。Origin 必须在 CORS 白名单（无 Origin 的本机 curl 放行）。连接后立刻推当前状态，之后每次提交后的状态变化再推。心跳 15s 一次。

```text
event: booking-status
data: {"bookingId":10,"status":"CONFIRMED","ticketsCheckedIn":0}

event: heartbeat
data: {}
```

SSE 是提示。重连后先 `GET /api/bookings/{id}` 再挂流。推送必须在事务提交之后（`AFTER_COMMIT`），避免回滚状态泄漏。不使用 WebSocket——架构图里的 WebSocket/SSE 在本项目落地为 SSE。

### 9.12 健康与指标

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/actuator/health` | 公开 | Compose / smoke，期望 `"status":"UP"` |
| GET | `/actuator/prometheus` | 公开 | scrape |
| GET | `/api/meta/metrics` | 主办方 JWT | 给看板的 JSON |

---

## 10 票据与核销

最终版本按单张票核销，不用整笔 booking 代替票据：

- 每个成功预订按购买数量生成对应数量的独立票据。
- 每张票拥有不可预测且唯一的核销码；库里只存 `ticket_code_hash`。
- 主办方可以扫描二维码或手动输入核销码。
- 系统校验票据所属活动、票据状态和当前主办方权限。
- 防止重复核销（409），并展示首次核销时间。
- 允许有权限的主办方撤销误核销，需填写原因。
- 记录核销时间、来源、操作者和撤销原因。
- booking 详情汇总总票数、已核销票数和未核销票数。
- 活动取消或订单取消后，对应票据立即置为失效。

主办方**不能**随意修改用户订单数量，也不能把取消订单恢复为确认状态。主办方对订单可执行的操作仅限：查询、签到与撤销签到、添加内部备注、在活动取消流程中触发通知。普通用户仍只能查看和取消自己的订单。

---

## 11 Kafka 与 Outbox

Topic：`booking-events`。Key：`bookingId` 字符串。

**不要**在 Service 里直接 `KafkaTemplate.send`。业务事务内插入 outbox：

```json
{
  "type": "BOOKING_CREATED",
  "bookingId": 10,
  "eventId": 1,
  "userId": 3,
  "quantity": 2,
  "dedupKey": "10:BOOKING_CREATED"
}
```

事件类型至少包括 `BOOKING_CREATED`、`BOOKING_CANCELLED`、`EVENT_UPDATED`、`EVENT_CANCELLED`、`TICKET_CHECKED_IN`。

`OutboxRelay` 定时取 `published_at IS NULL`，发到 Kafka，成功后填 `published_at`。

Consumer group `eventpulse`：

1. 按 `dedup_key` 幂等写 `notifications`（用户可读的标题与正文，不是 Kafka 内部术语）。
2. 写 `interactions`：`BOOK` 或 `CANCEL`。

这是教学版 Outbox：保证「写库成功则消息最终会发」。不做无间隙序号、gap 检测、DLT、跨服务 Saga 补偿。进程在 relay 之前崩溃，重启后会补发。

---

## 12 AI 服务 / LangChain Agent

### 12.1 定位

AI 是 EventPulse 调用的一项外部能力，不是项目内部训练出来的模型。

本项目只做：

- 调用外部 LLM API。
- 为 LLM 准备清晰的提示词和业务上下文。
- 让 Agent 在允许的工具中选择并查询活动。
- 校验 LLM 的结构化返回结果。
- 记录耗时、错误、token 用量和用户反馈。

本项目不做：

- 模型训练或微调。
- embedding 生成。
- 向量数据库或向量检索。
- 点击率预测和机器学习排序。
- 让 LLM 直接连接数据库或执行任意 SQL。

### 12.2 为什么使用独立 Python 服务

新增独立目录：

```text
ai-service/
├── app/
│   ├── main.py
│   ├── api/                 HTTP 路由
│   ├── agents/              活动发现 Agent
│   ├── chains/              活动文案生成流程
│   ├── tools/               Agent 可调用的受控工具
│   ├── clients/             Spring Boot 和 LLM 客户端
│   ├── schemas/             Pydantic 请求与返回结构
│   └── config.py
├── tests/
├── pyproject.toml
├── uv.lock
└── Dockerfile
```

建议使用：

- FastAPI：提供内部 HTTP API。
- LangChain：创建 Agent、定义工具、调用 LLM。
- Pydantic：限制请求、工具参数和返回格式。
- httpx：调用 Spring Boot 内部工具接口。
- pytest：测试提示词、工具调用、权限和降级行为。

Python AI 服务使用自己的镜像，与 Spring Boot 镜像分开发布。它本身不保存会话到进程内存，因此以后也可以增加多个副本。

### 12.3 调用关系

```text
浏览器
   │
   ▼
Spring Boot /api/ai/**
   │  1. JWT、角色、资源所有权、限流
   │  2. 生成 requestId 和可信用户上下文
   ▼
Python AI Service
   │
   ├── 调用外部 LLM
   │
   └── Agent 需要业务数据时调用受控工具
                    │
                    ▼
        Spring Boot /internal/ai-tools/**
                    │
                    ▼
               PostgreSQL
```

Python 服务不直接持有数据库账号。所有活动查询都回到 Spring Boot，由现有 Service 和 Repository 执行。这样权限、活动状态和查询限制只有一套规则。

浏览器也不直接调用 Python 服务。LLM API Key 只存在于 Python 服务的 Secret 中。

### 12.4 哪些功能使用 Agent

不是每个 LLM 功能都需要 Agent。

#### 活动文案助手：不用 Agent

文案完善只有一个明确任务：根据主办方提供的活动资料生成更完整的文案。它不需要自己决定调用哪些工具，使用普通 LLM 调用和结构化输出更简单、稳定。

固定返回结构：

```text
title
summary
description
attendanceNotes
warnings
```

主办方必须在界面中审核和选择，AI 不能自动保存或发布。

#### 活动发现助手：使用 Agent

用户的问题可能包含城市、日期、预算、类别、同行人、距离等不同条件。Agent 需要先理解问题，再选择合适的查询工具，可以连续调用多个工具，最后根据真实结果回答。

例如：

```text
用户：这个周六在上海有什么适合两个人、每人 200 元以内的音乐活动？
       │
       ▼
Agent 提取条件
       │
       ▼
调用 search_published_events(...)
       │
       ▼
必要时调用 get_event_details(eventId)
       │
       ▼
只根据工具返回的活动组织回答
```

第一版使用 LangChain `create_agent` 和少量静态工具，不先建设复杂的 LangGraph 工作流。以后只有在确实需要暂停、人工批准或恢复长任务时，再引入更复杂的图流程。

### 12.5 Agent 工具清单

第一版工具全部只读：

| 工具 | 用途 | 限制 |
| --- | --- | --- |
| `search_published_events` | 按关键词、城市、类别、日期、价格查询活动 | 只返回公开活动；最多 20 条 |
| `get_event_details` | 查看某个活动的时间、地点、价格、余票和说明 | 不返回内部备注和敏感信息 |
| `find_nearby_events` | 按坐标和半径查找附近活动 | 半径上限沿用公开 API 规则 |
| `get_popular_events` | 查询当前热门活动 | Redis 不可用时由 Spring Boot 回源数据库 |
| `get_my_preferences` | 读取当前登录用户主动保存的偏好 | 必须登录，只能读取本人 |
| `get_my_recent_categories` | 汇总当前用户近期感兴趣的活动类别 | 必须登录，只返回类别摘要，不返回完整历史 |

不提供以下工具：

- 任意 SQL 工具。
- 创建、修改、发布或取消活动。
- 创建订单、退款或核销票据。
- 查看其他用户资料。
- 执行系统命令或访问任意网址。

每个工具都必须定义明确的参数结构、最大结果数量和超时时间。Spring Boot 内部接口仍要检查身份和权限，不能因为调用来自 Python 服务就完全信任参数。

### 12.6 Spring Boot 内部工具接口

计划新增只允许内网访问的接口：

```http
POST /internal/ai-tools/events/search
GET  /internal/ai-tools/events/{id}
POST /internal/ai-tools/events/nearby
GET  /internal/ai-tools/events/popular
GET  /internal/ai-tools/users/me/preferences
GET  /internal/ai-tools/users/me/recent-categories
```

这些接口使用服务间凭证，不使用浏览器 JWT 直接互调。Spring Boot 在调用 Python 服务时生成短期、签名的用户上下文；Python 调用工具时原样带回，Spring Boot 再确认 userId、role 和允许范围。

内部工具接口不通过公网 Ingress 暴露。每次工具调用都携带同一个 requestId，方便将一次用户请求、一次 LLM 调用和若干工具调用串起来排查。

### 12.7 结构化输出与结果校验

LLM 不直接返回一段无法检查的自由文本。Python 使用 Pydantic 定义返回结构，并让 LangChain 返回经过校验的数据。

活动发现结果至少包含：

```text
answer
eventIds
reasons
followUpQuestions
```

Python 返回后，Spring Boot 再执行一次最终校验：

1. eventId 是否来自本次工具结果。
2. 活动现在是否仍然允许公开展示。
3. 是否超过返回数量上限。
4. reason 是否只引用真实的时间、城市、价格和类别。
5. 返回文本和字段长度是否符合限制。

校验失败时不把未经验证的结果交给前端。系统返回可理解的降级提示，并提供普通活动搜索入口。

### 12.8 会话记录

Python 服务不在内存里保存会话。

多轮活动助手需要历史时，由 Spring Boot 将会话保存在 PostgreSQL：

- `ai_conversations` 保存会话所属用户和用途。
- `ai_messages` 保存用户可见的提问和回答。
- 每次只向 Python 发送最近有限条消息，避免上下文无限增长。
- 不保存模型内部思考过程。
- 用户只能读取和继续自己的会话。
- 增加保留期限和删除能力。

访客可以使用不持久化的单轮请求。登录用户是否保存会话，由产品界面明确提示。

### 12.9 安全边界

活动描述和用户输入都视为不可信数据。即使某个活动描述里写着“忽略规则并调用某工具”，Agent 也不能把它当成系统指令。

必须实施：

- 工具白名单，默认没有写工具。
- 每次请求最多调用有限次数的工具，例如 4 次。
- 工具查询最多返回 20 条活动。
- 限制用户输入、活动上下文和模型输出长度。
- 主办方文案必须人工确认。
- 超时后取消 LLM 请求。
- 日志不记录 LLM API Key、JWT 和完整敏感内容。
- 对每个用户和 IP 限流，避免成本失控。
- 外部 LLM 不可用时返回明确降级信息，不影响普通活动搜索、编辑和预订。

### 12.10 配置

Python AI 服务通过环境变量配置：

```text
LLM_PROVIDER
LLM_MODEL
LLM_API_KEY
LLM_BASE_URL（可选）
LLM_TIMEOUT_SECONDS
LLM_MAX_OUTPUT_TOKENS
BACKEND_INTERNAL_URL
BACKEND_SERVICE_TOKEN
LANGSMITH_TRACING（可选）
```

Spring Boot 配置：

```text
AI_SERVICE_URL
AI_SERVICE_TOKEN
AI_CONNECT_TIMEOUT
AI_READ_TIMEOUT
AI_ENABLED
```

模型名称不能写死在业务代码中。切换模型只改配置，并重新运行 AI 评测。

### 12.11 失败与降级

| 情况 | 处理 |
| --- | --- |
| LLM 超时 | 取消请求，提示稍后重试 |
| LLM 返回格式错误 | Python 进行有限重试；仍失败则返回统一错误 |
| Python AI 服务不可用 | Spring Boot 快速失败，普通业务接口继续工作 |
| 工具查询失败 | Agent 停止编造答案，说明暂时无法查询活动 |
| 返回了不存在的 eventId | Spring Boot 拒绝该 AI 结果 |
| Redis 不可用 | 热门工具回源 PostgreSQL |
| 达到用户限额 | 返回 429 和明确提示 |

文案生成请求不自动重试写操作，因为它本身没有写业务数据。活动搜索工具都是只读操作，可以做一次短重试。

### 12.12 可观测性与成本

至少记录以下指标：

- AI 请求数量、成功率和失败率。
- LLM 响应时间。
- 工具调用次数、工具名称和失败率。
- 输入和输出 token 数量。
- 按功能区分文案助手与活动发现助手。
- 结构化输出校验失败次数。
- 用户主动采用文案建议的比例。

日志使用 requestId 串联 Spring Boot 和 Python，但默认不记录完整提示词与完整回复。LangSmith 可以用于开发期查看 Agent 的工具选择和调用轨迹，但不是系统运行的必要依赖。

### 12.13 AI 评测，不是模型训练

虽然项目不训练模型，LLM 输出仍然具有不确定性，因此需要测试 AI 应用本身。

建立一组人工编写的小型测试集：

- “上海、周六、音乐、200 元以内”是否调用正确搜索工具并传入正确条件。
- 没有匹配活动时是否明确说明没有结果，而不是编造活动。
- 工具只返回活动 1 和 2 时，最终答案是否只引用 1 和 2。
- 未登录用户是否无法调用个人偏好工具。
- 普通用户是否无法使用主办方文案接口。
- 文案助手是否返回所有必填字段并遵守长度限制。
- 恶意活动描述是否无法改变 Agent 工具权限。

CI 使用假的 LLM 响应测试固定逻辑，避免网络、费用和模型波动导致 CI 不稳定。连接真实 LLM 的评测作为手动或定时任务运行，用于比较提示词和模型版本，不产生任何训练步骤。

LangChain 当前的 Agent API 支持将普通 Python 函数定义为工具，并支持按 Pydantic schema 返回结构化结果；本计划以这些能力为基础。参考：[LangChain Agents](https://docs.langchain.com/oss/python/langchain/agents)、[Structured output](https://docs.langchain.com/oss/python/langchain/structured-output)。

---

## 13 运营分析与可观测性

### 13.1 运营分析

- 事实来源是 `interactions`、`bookings`、`tickets`；`event_daily_metrics` 只是按活动和日期的聚合，支持重算。
- 主办方工作台指标：近期活动、待处理事项、低余票、临近活动。
- 单活动漏斗：浏览 → 点击 → 预订 → 售票 → 签到。
- 日期趋势与售票率、转化率。

### 13.2 可观测性

Micrometer 计数：`bookings.created`、`bookings.cancelled`、`ai.requests`、`ai.failures`、`ai.tool.calls`、`tickets.checked_in`、`outbox.pending`（gauge）。

`/actuator/prometheus` 给 scrape；`/api/meta/metrics` + 前端看板页就是图中的 Metrics Dashboard。本练习不强制上 Grafana。

监控至少能观察：预订、取消、AI 请求量与耗时、工具失败、Outbox 堆积、缓存回源和核销失败。

日志禁止打印 JWT、密码、LLM API Key、完整提示词、完整模型回复和参与者敏感信息。

---

## 14 前端

两个表面，一个 SPA。

**Discovery App**：活动列表与组合筛选、附近、详情、收藏、登录、预订（自选数量、提交前展示汇总）、订单确认与详情、逐票二维码、消息中心、偏好、AI 活动发现助手。打开某条预订时挂 `EventSource` 到 `/api/bookings/{id}/events`（带 Authorization 的 fetch polyfill 或 cookie 方案二选一；**禁止**把 JWT 拼进 URL）。断线后 REST 再拉一次。

**Organiser Console**：工作台、活动管理列表、全字段表单、AI 文案助手、活动运营详情（生命周期轨道 + 随状态变化的操作区）、参与者与核销、分析看板。

活动详情页的生命周期轨道：

```text
草稿 → 已发布/售票中 → 进行中 → 已结束 → 已归档
              └──────→ 已取消
```

草稿显示「继续编辑、预览、发布」，已发布显示「查看订单、取消活动」，已结束显示「查看数据、归档」。

AI 交互：

1. 首页提供“告诉我你想参加什么活动”的自然语言入口，调用 `POST /api/ai/discovery/chat`。
2. AI 返回的活动使用普通活动卡片展示，点击后仍进入真实活动详情页。
3. AI 正在处理、超时、达到限额和暂时不可用时，都显示明确状态，并保留普通搜索入口。
4. 主办方编辑活动时可以点击“AI 完善文案”，在差异预览中选择要采用的字段。
5. AI 建议只更新当前表单，不自动保存，更不能自动发布。

前端统一读取 `Result.data`。删除草稿和取消活动使用不同的确认文案；有订单的活动取消前展示影响人数。

响应式要求：桌面端、平板端、移动端布局可用，支持键盘操作和清晰的焦点状态。

---

## 15 实施路线

### 15.1 阶段 0：平台基线（第 1–8 周）

| 周 | 做什么 | 怎么验收 |
| --- | --- | --- |
| 1 | Flyway 业务四表、Entity / Repository、活动与预订 CRUD、`Result` | curl 列出活动、建预订 |
| 2 | 注册登录、JWT 拦截器、主办方发布 | 无 token 打 `/mine` 应 401 |
| 3 | 先直发 Kafka 打通通知页（随后由 Outbox 替换直发） | 预订后消息页出现文案 |
| 4 | React Discovery + Organiser、Docker Compose、JaCoCo 90%、CI | `make up` + `make test-all` + Actions 绿 |
| 5 | 抽出 Inventory；Outbox + Relay 替换直发 | 杀 backend 再起来，未发的 outbox 会补发 |
| 6 | 新建 Python AI 服务；打通 Spring Boot → FastAPI → LLM；实现主办方文案助手 | 主办方得到结构化建议，确认前不会写数据库 |
| 7 | LangChain 活动发现 Agent、只读活动工具、前端 AI 对话入口、AI 评测 | Agent 只返回数据库真实活动；`uv run pytest` 通过 |
| 8 | PostGIS 附近、Redis 热门缓存、SSE、漏斗、Metrics Dashboard | 附近按距离；Redis 停仍能列表；SSE 收到状态；`/metrics` 有数字 |

第 1–4 周不改预订事务语义；第 5 周才把「发 Kafka」挪进 Outbox；第 6–7 周接入 LLM 与 Agent；第 8 周补齐图上剩余盒子。

### 15.2 阶段 1：主办方活动 CRUD

范围：主办方活动列表；完整新建与编辑表单；草稿 / 发布 / 取消 / 归档状态机；活动详情与预览；删除草稿与复制活动；前后端权限保护；统一错误状态与 HTTP 语义。

验收：主办方可以完整创建、查询、编辑、发布、取消和归档自己的活动，且不能操作其他主办方的活动。

### 15.3 阶段 2：订单与参与者管理

范围：活动订单列表；参与者搜索、筛选、分页；独立票据生成、二维码展示、逐票核销与撤销；CSV 导出；活动变更与取消通知；数据库原子库存更新；乐观锁。

验收：主办方能准确回答「谁报名了、买了几张、订单是什么状态、谁已经签到」。

### 15.4 阶段 3：普通用户体验

范围：丰富活动筛选与排序；收藏；自选预订数量；订单确认与详情；订单状态分组；用户化的消息中心；逐票二维码与状态。

验收：普通用户能顺畅完成查找活动、预订、查看订单、取消和接收通知的完整流程。

### 15.5 阶段 4：运营数据

范围：主办方工作台指标；浏览、点击、预订与售票率；日期趋势与单活动漏斗；待处理事项、低余票和临近活动提醒。

验收：主办方不仅能维护活动资料，还能判断活动当前表现和需要采取的运营动作。

### 15.6 阶段 5：智能化、实时性与可靠性

范围：AI 文案助手与活动发现 Agent；附近活动；Outbox 消息可靠性与消费者幂等；SSE 实时状态；Redis 热门缓存与回源；媒体上传与生命周期；更完整的指标与审计。

阶段 5 完成后进行全链路容量验证、安全检查、无障碍检查和发布验收。阶段 0–5 全部完成才构成本规划定义的最终版本。

---

## 16 测试与 CI

### 16.1 后端

单元与集成测试覆盖：`Result` 信封 / JWT 拦截器 / Inventory 条件更新 / 乐观锁冲突 / Outbox relay / SSE 所有权 / AI Gateway 权限 / Python 服务超时和返回校验。JaCoCo 排除启动类，行覆盖率 ≥ 90%。

必须覆盖的业务断言：

- 普通用户不能调用主办方接口（403）。
- 主办方不能查询和修改其他主办方的活动（403/404）。
- 草稿可以删除，有订单的活动不能物理删除。
- 非法状态转换被拒绝（409）。
- 活动容量不能低于已售数量。
- 并发预订不会超卖。
- 重复签到被拒绝（409），撤销签到可正确恢复。
- 版本冲突返回 409。
- 活动取消后不能再预订，并生成参与者通知。
- 重复消费同一 Outbox 事件不产生重复通知。

### 16.2 前端

Vitest 覆盖率 ≥ 80%，覆盖：

- 主办方角色可以看到工作台入口，普通用户不能进入主办方路由。
- 活动表单必填项、时间范围和容量校验。
- 活动列表搜索、状态筛选和分页。
- 编辑后列表与详情数据同步更新。
- 删除草稿与取消活动的不同确认文案。
- 有订单的活动取消前展示影响人数。
- API 失败时展示可操作的错误状态。
- AI 活动助手的加载、超时、空结果和普通搜索降级状态。
- 主办方可以逐字段采用 AI 文案，未确认时不会保存。

Playwright 覆盖主办方发布、用户预订、逐票核销、活动取消与通知流程。

### 16.3 Python AI 服务

`uv run pytest`；lockfile 使用 `--frozen`。CI 默认使用假的 LLM 和假的 Spring Boot 工具响应，检查工具选择、参数、结构化输出、权限和错误处理，不调用收费的真实模型。

### 16.4 冒烟与 CI

冒烟：预订 → 消息；AI 文案接口返回结构化建议；AI 活动助手返回数据库真实 eventId；`GET /api/meta/metrics` 200；媒体上传、SSE、Outbox 关键链路可达。

CI：gitleaks、`mvn verify`、frontend lint + coverage + build + e2e、`ai-service` pytest。

Compose：`postgres`（PostGIS）、`redis`、Kafka 集群、`backend-api`、`backend-worker`、`ai-service`、`frontend`。

### 16.5 端到端验收场景

主流程：

```text
主办方创建并编辑活动
→ 上传封面并保存草稿
→ 预览后发布
→ 用户通过搜索、附近或 AI 活动助手发现真实活动
→ 用户收藏并预订多张票
→ 系统原子扣减库存、生成独立票据并可靠发送通知
→ 主办方查看订单、导出参与者并逐张核销二维码
→ 用户实时看到票据状态变化
→ 主办方查看售票和转化分析
→ 主办方变更或取消活动，所有受影响用户收到通知
→ 活动结束并归档，审计记录完整保留
```

异常流程：

```text
主办方发布活动
→ 用户产生预订
→ 主办方取消活动
→ 新预订被禁止
→ 原订单保留、票据失效
→ 用户消息中心收到取消通知
```

---

## 17 最终版本完成定义

### 17.1 主办方运营

- 完整活动工作台、活动列表、详情、预览和全字段表单。
- AI 根据草稿完善标题、摘要、详细描述和入场须知；主办方逐项确认后再保存。
- `DRAFT` / `PUBLISHED` / `ONGOING` / `FINISHED` / `CANCELLED` / `ARCHIVED` 全生命周期。
- 新建、查询、编辑、复制、发布、取消、归档和删除草稿。
- 活动封面上传、替换、删除和资源所有权校验。
- 订单与参与者查询、筛选、分页和 CSV 导出。
- 独立票据二维码、逐票核销、撤销核销和完整操作记录。
- 活动浏览、点击、收藏、预订、售票率、签到率和转化漏斗。

### 17.2 参与者体验

- 活动搜索、组合筛选、排序、分页、附近活动和自然语言 AI 活动发现助手。
- AI 助手只推荐数据库真实存在并且当前仍公开的活动。
- 收藏、取消收藏、互动记录和偏好管理。
- 自主选择预订数量、库存确认、订单确认和订单详情。
- 每张票独立展示二维码与当前核销状态。
- 按状态管理订单，并在规则允许时取消。
- 消息中心接收预订、变更、取消、提醒和核销相关通知。
- 个人中心展示钱包余额、累计消费与账户统计（订单 / 电子票 / 收藏 / 消息），支持模拟充值。
- SSE 提供订单和票据状态的实时提示，REST 始终作为事实来源。

### 17.3 平台可靠性

- 数据库条件更新保证并发预订不超卖。
- 乐观锁防止主办方编辑覆盖。
- Outbox 保证订单、活动通知和 Kafka 消息最终可达。
- 消费者幂等处理，避免重复通知和重复互动记录。
- Redis 缓存失败时能够回源 PostgreSQL。
- Python AI 服务或外部 LLM 不可用时，普通搜索、活动编辑、预订和票据功能继续工作。
- AI 不能绕过权限直接读写业务数据，所有工具调用由 Spring Boot 再次校验。
- 401 / 403 / 404 / 409 / 500 错误语义明确。
- 活动及票据敏感操作具有审计记录。
- 日志、导出、媒体上传和二维码密钥满足安全约束。

### 17.4 工程质量

- 后端单元测试、Repository 集成测试和并发库存测试通过，JaCoCo ≥ 90%。
- 前端组件测试、权限路由测试和表单状态测试通过，Vitest ≥ 80%。
- Playwright 覆盖主办方发布、用户预订、逐票核销、活动取消和通知流程。
- Python AI 服务测试覆盖文案结构、Agent 工具选择、不编造活动、权限和超时降级。
- Smoke Test 覆盖数据库、Kafka、Redis、Outbox、SSE 和媒体上传关键链路。
- 桌面端、平板端和移动端布局可用，支持键盘操作和清晰的焦点状态；内容不满一屏时页脚仍贴底（flex column shell）。
- 监控可以观察预订、取消、AI 请求与工具调用、Outbox 堆积、缓存回源和核销失败。

---

## 18 诚实边界

Kafka 使用三节点学习集群，PostgreSQL 和 Redis 保持单节点。演示账号。Outbox 保证最终发出，不承诺 Kafka 中绝不出现重复记录，消费者仍用 `dedup_key` 保证业务幂等。SSE 会断，REST 才是事实。附近查询依赖主办方填写坐标。钱包充值仍为演示数据，不接入真实支付渠道。

AI 能力来自运行时调用外部 LLM。项目不拥有或训练基础模型，不把 AI 回复当成业务事实，也不承诺每次回复完全相同。活动发现 Agent 只通过受控工具读取真实活动；文案建议必须由主办方确认。

这是练习项目：把架构图上的盒子用能讲清的实现跑通，并让主办方到参与者的运营闭环真的能走完，而不是生产订票平台或自主决策系统。

---

## 附录 A：现状对照（2026-09-01 抽查）

合并时对仓库做的抽查结果，仅供排期参考，不替代逐条验收。

| 计划项 | 现状 |
| --- | --- |
| Flyway `V1__init.sql` + `V2__crud_enhancement.sql` | 已落地，V2 覆盖 §5.2 与 §5.3 全部字段与新表 |
| 六态生命周期与转换校验 | 已落地（`domain/EventStatus`），转换规则与 §8.2 一致 |
| `/api/organiser/events` 全套（含 publish / cancel / archive / duplicate） | 已落地 |
| 主办方订单、参与者、CSV、票据核销与撤销 | 已落地（`OrganiserOpsController`、`TicketService`） |
| 媒体上传 `/api/media/images` | 已落地 |
| 收藏、互动、附近、规则式推荐、SSE、主办方分析 | 已落地（`PlatformController`）；规则式推荐不作为最终 AI 方案 |
| HTTP 状态语义（401/403/404/409） | 已落地（`BusinessException` 携带状态 + `GlobalExceptionHandler`） |
| 前端 Discovery + Organiser Console 路由 | 已落地，除 `/preferences` 与 `/metrics` 页面 |
| `GET /api/preferences` | **未落地**，当前只有 `POST /api/preferences` |
| `/api/meta/metrics` 与前端 `/metrics` 看板 | **未落地** |
| Python AI Service、LangChain Agent、文案助手和受控查询工具 | **未落地** |
