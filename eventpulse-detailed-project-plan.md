# EventPulse 开发计划

Spring Boot 4 · Kafka · PostgreSQL（PostGIS + pgvector）· Redis · Python ML。分层对齐 firmament：`Controller → Service → Repository`。统一 `Result`，JWT 拦截器鉴权。目标架构对齐四层图：Discovery / Organiser Console → Spring Boot 五模块 → Kafka / Postgres / Redis → Ranking Model + Outbox + Metrics。

不引入真实支付、钱包、超卖锁协议。推荐只做预测与解释，不当交易或库存的事实源。

Kaiwen Yao · 2026-08-31

## 目录

1. [目标、成功标准与边界](#1-目标成功标准与边界)
2. [目标架构（对照架构图）](#2-目标架构对照架构图)
3. [角色、流程与页面](#3-角色流程与页面)
4. [分层与模块](#4-分层与模块)
5. [数据模型](#5-数据模型)
6. [鉴权](#6-鉴权)
7. [统一响应与错误](#7-统一响应与错误)
8. [HTTP API（完整清单）](#8-http-api完整清单)
9. [Kafka 与 Outbox](#9-kafka-与-outbox)
10. [ML 预测 / Ranking Model](#10-ml-预测--ranking-model)
11. [前端](#11-前端)
12. [可观测性](#12-可观测性)
13. [实施路线](#13-实施路线)
14. [测试与 CI](#14-测试与-ci)
15. [诚实边界](#15-诚实边界)

---

## 1 目标、成功标准与边界

用最少代码讲清这些事：

1. **HTTP CRUD**：注册登录、活动目录、预订
2. **鉴权**：JWT 拦截器，不用 Spring Security 过滤器链
3. **库存**：`Inventory` 独占 `sold` / 余票，预订只调它
4. **消息**：同一事务写 Outbox，relay 发 Kafka，消费者写通知
5. **实时**：预订状态用 SSE 推，REST 仍是事实源
6. **ML 预测**：Ranking Model 给活动打分；离线时间切分评估，报告标注 SYNTHETIC

| 维度 | 门槛 | 证据 |
| --- | --- | --- |
| CRUD + 鉴权 | `make up` 能注册、浏览、预订、取消；无 token 打需登录接口返回 401 | smoke + 拦截器单测 |
| 库存 | 余票不足拒绝；取消把 `sold` 减回；只有 Inventory 改 `sold` | Service 单测 |
| 消息 | 预订 / 取消后「消息」页出现 `Kafka 已处理：BOOKING_*`；杀进程重放 Outbox 仍能发出 | smoke + Outbox 单测 |
| 实时 | 已打开的预订 SSE 在状态变化后收到 `booking-status` | 单测 / 手工 |
| 推荐效果 | V1 相对热门基线有离线对照，且没有未来泄漏 | `ml/` NDCG@10、Recall@10、coverage、diversity、bootstrap CI |
| 质量 | 后端 JaCoCo 行覆盖率 ≥ 90%；前端 Vitest ≥ 80%；CI 在 `main` 全绿 | `mvn verify`、Vitest、`uv run pytest` |

非目标：真实收单、分布式锁协议、完整 Saga 编排器、生产级运维、宣称合成数据代表真实商业效果。

---

## 2 目标架构（对照架构图）

```
React Discovery App / Organiser Console
        │  REST + SSE
        ▼
Spring Boot ─ Catalogue · Recommendation · Booking · Inventory · Notification
        │
        ▼
Kafka · PostgreSQL + PostGIS + pgvector · Redis
        │
        ▼
Ranking Model · Outbox · Metrics Dashboard
```

| 图中盒子 | 计划落地 | 对照本文件 |
| --- | --- | --- |
| React Discovery App | `/` 发现、详情、推荐、预订、消息、偏好 | §3、§11 |
| Organiser Console | `/organiser` 发布 / 改 / 取消 + 漏斗数字 | §3、§11 |
| REST | 全部 JSON API，包在 `Result` | §8 |
| SSE | `GET /api/bookings/{id}/events`，状态变化推 `booking-status` | §8.9 |
| Catalogue | 活动搜索、详情、附近（PostGIS） | `CatalogueService` |
| Recommendation | 在线排序，调 Ranking Model | §8.7、§10 |
| Booking | 创建 / 查询 / 取消；调 Inventory；写 Outbox | §8.3 |
| Inventory | 独占 `capacity` / `sold`；预订不得直接改 `events.sold` | §4.2 |
| Notification | Kafka 消费后写 `notifications` | §8.4、§9 |
| Kafka | topic `booking-events` | §9 |
| PostgreSQL + PostGIS | 活动 `geography` 点；附近 `ST_DWithin`，半径上限 50 km | §5、§8.2 |
| pgvector | `events.embedding vector(64)`，余弦 `<=>` | §5、§10 |
| Redis | 热门活动 / 热门计数缓存，TTL 60s | §4.3 |
| Ranking Model | V0 热门 + V1 hash embedding；`ml/` 离线评估 | §10 |
| Outbox | 与预订同一事务插入 `outbox`；`OutboxRelay` 再发 Kafka（教学版，不做 gap/DLT） | §9 |
| Metrics Dashboard | Actuator Prometheus + `/api/meta/metrics` + 前端看板页 | §12 |

图里没有、也不做的：钱包、真实网关、锁协议 A/B、完整 Saga 状态机。Outbox 承担「写库与发消息同命运」；预订事务本身是单库本地事务，不跨服务编排。

---

## 3 角色、流程与页面

| 角色 | 页面 | 能做什么 |
| --- | --- | --- |
| 访客 | Discovery：列表 / 附近 / 详情 / 登录注册 / 热门推荐 | 浏览；推荐走 V0 |
| USER | 以上 + 预订、我的预订、消息、偏好、为你推荐 | 预订与取消；写互动；SSE 看状态 |
| ORGANISER | Organiser Console | 发布 / 改 / 取消自己的活动；看漏斗 |
| 推荐系统 | Ranking Model + `/api/recommendations` | 只输出排序与理由 |

注册接口不接受 `role`，一律写成 `USER`。演示主办方由 `demo` profile 播种。

普通用户主流程：

1. 注册 / 登录，拿到 JWT。
2. （可选）写入兴趣类别、常驻城市、可选坐标。
3. Discovery 浏览列表、附近或「为你推荐」；点进详情上报 `VIEW` / `CLICK`。
4. 预订：Booking → Inventory 扣减 → 同事务写 Outbox → relay 发 Kafka → 通知 + `BOOK` 互动；SSE 推 `CONFIRMED`。
5. 取消同理，发 `BOOKING_CANCELLED` 并记 `CANCEL`。

前端路由：

| 路径 | 表面 | 页面 | 鉴权 |
| --- | --- | --- | --- |
| `/` | Discovery | 搜索 + 附近 + 列表 + 推荐分区 | 公开 |
| `/events/:id` | Discovery | 活动详情 / 预订 | 浏览公开，预订需登录 |
| `/login` | Discovery | 登录 / 注册 | 公开 |
| `/preferences` | Discovery | 兴趣类别、城市、坐标 | 登录 |
| `/bookings` | Discovery | 我的预订 / 取消；打开详情连 SSE | 登录 |
| `/notifications` | Discovery | Kafka 写入的消息 | 登录 |
| `/organiser` | Organiser Console | 发布 / 修改 / 取消 + 漏斗 | 主办方 |
| `/metrics` | Organiser Console | 预订 / 推荐计数看板 | 主办方 |

演示账号（`demo` profile）：

| 角色 | 邮箱 | 密码 |
| --- | --- | --- |
| USER | `user@eventpulse.dev` | `User123456` |
| ORGANISER | `organiser@eventpulse.dev` | `Organiser123456` |

---

## 4 分层与模块

```
Controller  → REST / SSE；返回 Result<T>；不写业务规则
Service     → 五个模块（见下）
Repository  → Spring Data JPA / JdbcTemplate（PostGIS、pgvector SQL）
Entity      → 表行；不直接作为 HTTP 响应
dto         → *Request 入参，*Vo 出参（Java record，不单独建 vo 包）
Interceptor → JWT；公开路径白名单；可选 token 的接口要解析但不强制
outbox      → 与业务同一事务写入；OutboxRelay 轮询发 Kafka
kafka       → Consumer 写 notifications 和 BOOK/CANCEL 互动
redis       → 热门缓存
ml/         → 离线评估，与后端共用同一套 hash embedding
```

### 4.1 五个 Spring Boot 模块

| 模块 | 职责 | 禁止 |
| --- | --- | --- |
| Catalogue | 列表、详情、附近、主办方写活动、写 embedding | 改 `sold`；做推荐打分 |
| Inventory | `reserve(eventId, qty)` / `release(eventId, qty)`；读 `remaining` | 发 Kafka；改订单状态 |
| Booking | 创建 / 取消 / 查询；调 Inventory；写 Outbox | 直接 `event.setSold` |
| Recommendation | 召回、打分、冻结 cursor；读 Redis 热门 | 改库存或订单 |
| Notification | 消费 Kafka、落 `notifications`、供列表查询 | 改预订 |

VO 放在 `dto` 包里。Entity 不出接口：`User.password` 不得出现在 `UserVo`；`EventVo.remaining` 来自 Inventory，不是列。

### 4.2 Inventory

预订成功：`UPDATE events SET sold = sold + ? WHERE id = ? AND status = 'PUBLISHED' AND sold + ? <= capacity`，0 行 → `"余票不足"`。取消：`sold = GREATEST(sold - qty, 0)`。并发下靠这一条条件更新，不引入显式锁协议。

### 4.3 Redis

| key | 值 | TTL |
| --- | --- | --- |
| `popular:events` | 热门 `EventVo[]` JSON | 60s |
| `popular:counts` | eventId → BOOK+CLICK 计数 | 60s |

Redis 挂了必须回源 PostgreSQL，推荐和列表不能 500。Compose 加 `redis:8`，端口 6379。

---

## 5 数据模型

业务表见 `backend/src/main/resources/db/migration/V1__init.sql`，后续 Flyway 只加列/加表，不改已有列含义。Postgres 镜像需带 **PostGIS** 与 **pgvector**。

### 5.1 业务表

| 表 | 关键字段 |
| --- | --- |
| users | email UNIQUE, password (BCrypt), name, role (`USER` / `ORGANISER`) |
| events | title, description, category, city, starts_at, price_cents, capacity, sold, organiser_id, status, created_at, **location geography(Point,4326)**, **embedding vector(64)** |
| bookings | user_id, event_id, quantity, status (`CONFIRMED` / `CANCELLED`), created_at |
| notifications | booking_id, message, created_at |

活动状态：`PUBLISHED` / `CANCELLED`。类别：`music` / `tech` / `sports` / `art` / `food`。

`location` 可空；有坐标才进附近检索。`embedding` 在 create/update 时由 `EmbeddingService` 写入。

### 5.2 ML 与消息表

| 表 | 关键字段 | 用途 |
| --- | --- | --- |
| user_preferences | user_id PK, categories `TEXT[]`, city, lat, lng | 显式兴趣；坐标供 nearby |
| interactions | id, user_id NULL, event_id, type, position, occurred_at | point-in-time 行为 |
| recommendation_requests | id, user_id NULL, section, model_version, feature_version, candidate_ids JSON, query_as_of, expires_at | 冻结候选 |
| outbox | id, topic, payload JSON, created_at, published_at | 与预订同事务；relay 发出后填 published_at |

`interactions.type`：`VIEW` / `CLICK` / `SAVE` / `UNSAVE` / `BOOK` / `CANCEL`。`BOOK` / `CANCEL` 只由 Kafka 消费者写。

不变量：Recommendation 不得改 `sold` / `bookings.status`。特征不得使用 `queryAsOf` 之后的互动。

---

## 6 鉴权

`JwtService` 签发 HS256，claims：`userId`、`role`。Header：`Authorization: Bearer <token>`。SSE 同样走 Header，**token 不得放进 query**。

`JwtInterceptor` 拦截 `/api/**`。公开路径：

- 所有 `OPTIONS`
- `POST /api/auth/login`、`POST /api/auth/register`
- `GET /api/events`、`GET /api/events/{数字id}`
- `GET /api/recommendations`
- `GET /actuator/health`、`GET /actuator/prometheus`

`GET /api/recommendations` 可选 JWT：有 token 则个性化，没有则 V0，不要 401。公开路径若带 `Bearer` 仍应 parse。

其余无 token → HTTP 401，`{"code":0,"msg":"未登录或 token 无效"}`。

主办方写接口与 `/api/meta/metrics`：Service 校验 `role == ORGANISER`。注册 DTO 不接受 `role` / `status` / `organiserId`。

---

## 7 统一响应与错误

成功 HTTP 200：`{"code":1,"msg":null,"data":{}}`。无 body 时 `data` 为 `null`。

业务错误 HTTP 400：`{"code":0,"msg":"余票不足","data":null}`。

校验失败 HTTP 400，`msg` 为第一个字段错误。未登录 HTTP 401。未捕获 HTTP 500。

SSE 不是 `Result`：`Content-Type: text/event-stream`，事件名为 `booking-status` / `heartbeat`。

时间 ISO-8601 UTC。金额整数分 `priceCents`。

---

## 8 HTTP API（完整清单）

基址：`http://localhost:8080`。Compose 前端 `http://localhost:3000`，`/api` 反代到 backend。

### 8.1 认证 `/api/auth`

#### `POST /api/auth/register` 公开

```json
{ "email": "a@b.com", "password": "secret1", "name": "Kaiwen" }
```

| 字段 | 约束 |
| --- | --- |
| email | 非空、合法邮箱 |
| password | 非空、6–64 |
| name | 非空、最长 50 |

成功：

```json
{
  "token": "<jwt>",
  "user": { "id": 1, "email": "a@b.com", "name": "Kaiwen", "role": "USER" }
}
```

邮箱已注册 → `"邮箱已被注册"`。role 固定 `USER`。

#### `POST /api/auth/login` 公开

`{ "email", "password" }`。成功同 register。失败：`"邮箱或密码错误"`。

#### `GET /api/auth/me` JWT

`{ "id", "email", "name", "role" }`。不含 password、token。

### 8.2 Catalogue `/api/events`

`EventVo`：

```json
{
  "id": 1,
  "title": "城市脉搏 · 独立摇滚之夜",
  "description": "演示活动，可直接预订。",
  "category": "music",
  "city": "上海",
  "lat": 31.23,
  "lng": 121.47,
  "startsAt": "2026-09-14T12:00:00Z",
  "priceCents": 18000,
  "capacity": 300,
  "sold": 2,
  "remaining": 298,
  "organiserId": 2,
  "status": "PUBLISHED"
}
```

无坐标时 `lat` / `lng` 为 `null`。

`EventRequest`：

```json
{
  "title": "城市脉搏 · 独立摇滚之夜",
  "description": "可选",
  "category": "music",
  "city": "上海",
  "lat": 31.23,
  "lng": 121.47,
  "startsAt": "2026-09-14T12:00:00Z",
  "priceCents": 18000,
  "capacity": 300
}
```

| 字段 | 约束 |
| --- | --- |
| title | 非空、最长 200 |
| category / city | 非空、最长 50 |
| lat / lng | 可空；要就成对出现，纬度 [-90,90]，经度 [-180,180] |
| startsAt | 非空 |
| priceCents | ≥ 0 |
| capacity | ≥ 1；更新时不得小于当前 sold |

#### `GET /api/events` 公开

| 参数 | 说明 |
| --- | --- |
| city / category / q | 与现在相同 |
| lat, lng, radiusKm | 附近；三者要么都空，要么 lat+lng 必填。`radiusKm` 默认 10，上限 50 |
| limit | 默认 50 |

只返回 `PUBLISHED`。带坐标时按距离升序（PostGIS），否则按 `startsAt` 升序。无坐标的活动不进附近结果。

#### `GET /api/events/{id}` 公开

`id` 必须是数字。不存在 → `"活动不存在"`。已取消仍可按 id 看。

#### `GET /api/events/mine` 主办方 JWT

自己的全部活动（含取消），按 `startsAt` 降序。非主办方 → `"只有主办方可以管理活动"`。

#### `POST /api/events` 主办方 JWT

`sold=0`，`PUBLISHED`，写 `location` 与 `embedding`。返回 `EventVo`。

#### `PUT /api/events/{id}` 主办方 JWT

只能改自己的。重算 embedding；坐标变更则更新 `location`。

#### `DELETE /api/events/{id}` 主办方 JWT

`status=CANCELLED`，不删行。`data=null`。

### 8.3 Booking `/api/bookings`

`BookingVo`：

```json
{
  "id": 10,
  "eventId": 1,
  "eventTitle": "城市脉搏 · 独立摇滚之夜",
  "quantity": 2,
  "status": "CONFIRMED",
  "createdAt": "2026-08-31T12:00:00Z"
}
```

#### `POST /api/bookings` JWT

`{ "eventId": 1, "quantity": 2 }`，`quantity` 1–10。

同一事务：Inventory.reserve → 插 `bookings` → 插 `outbox`。成功返回 `BookingVo`。

| 条件 | msg |
| --- | --- |
| 活动不存在 | 活动不存在 |
| 活动已取消 | 活动已取消，无法预订 |
| reserve 0 行 | 余票不足 |
| 未登录 | 请先登录 / 401 |

不做支付、不做幂等键。同一用户可多次预订同一活动。

#### `GET /api/bookings` JWT

当前用户全部预订，按 `createdAt` 降序。

#### `GET /api/bookings/{id}` JWT

只能看自己的。别人或不存在 → `"订单不存在"` 或 `"只能查看自己的订单"`。

#### `POST /api/bookings/{id}/cancel` JWT

仅 `CONFIRMED`。同一事务：Inventory.release → `CANCELLED` → Outbox `BOOKING_CANCELLED`。已取消 → `"订单已取消"`。

### 8.4 Notification `/api/notifications`

#### `GET /api/notifications` JWT

`NotificationVo`：`{ "id", "bookingId", "message", "createdAt" }`。`message` 形如 `Kafka 已处理：BOOKING_CREATED`。

### 8.5 偏好 `/api/preferences`

#### `GET /api/preferences` JWT

无记录不 404：`{ "categories": [], "city": null, "lat": null, "lng": null }`。

#### `PUT /api/preferences` JWT

```json
{ "categories": ["music", "tech"], "city": "上海", "lat": 31.23, "lng": 121.47 }
```

`categories` 为约定子集，可空。`lat`/`lng` 成对可选。

### 8.6 互动 `/api/interactions`

#### `POST /api/interactions` JWT

```json
{
  "requestId": "可选，对应某次推荐页",
  "events": [
    { "eventId": 1, "type": "VIEW", "position": 0 },
    { "eventId": 3, "type": "CLICK", "position": 2 }
  ]
}
```

| 字段 | 约束 |
| --- | --- |
| type | 仅 `VIEW` / `CLICK` / `SAVE` / `UNSAVE` |
| position | 可空，0-based |
| events | 1–50 条 |

成功：`{ "accepted": 2 }`。未知 `eventId` 忽略。禁止客户端传 `BOOK` / `CANCEL`。

### 8.7 Recommendation `/api/recommendations`

#### `GET /api/recommendations` 可选 JWT

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| section | `for-you` | `for-you` 个性化；`popular` 热门（可读 Redis）；`nearby` 用偏好坐标，否则 city，再否则 popular |
| limit | 10 | 1–50 |
| cursor | 空 | 冻结候选翻页 |

成功：

```json
{
  "requestId": "3f2a0000-0000-4000-8000-000000000001",
  "modelVersion": "v1-hash-embedding",
  "featureVersion": "f1-pref-pop-interact",
  "queryAsOf": "2026-08-31T22:00:00Z",
  "nextCursor": "eyJhbGciOiJIUzI1NiJ9",
  "items": [
    {
      "eventId": 1,
      "title": "城市脉搏 · 独立摇滚之夜",
      "category": "music",
      "city": "上海",
      "startsAt": "2026-09-14T12:00:00Z",
      "priceCents": 18000,
      "remaining": 298,
      "score": 7.42,
      "reasons": ["MATCHES_PREFERENCE", "EMBEDDING_MATCH", "POPULAR"]
    }
  ]
}
```

只召回 `PUBLISHED` 且 `startsAt > queryAsOf`。首次请求冻结 `candidate_ids`。过期 cursor → `"推荐结果已过期，请重新刷新"`。访客 `v0-popularity`。登录且向量可用 `v1-hash-embedding`。`reasons` 必须对得上特征。

### 8.8 Organiser 漏斗

#### `GET /api/organiser/funnel` 主办方 JWT

自己活动的 views / clicks / bookings / sold。给 Console 用，不是独立分析平台。

```json
[
  {
    "eventId": 1,
    "title": "城市脉搏 · 独立摇滚之夜",
    "status": "PUBLISHED",
    "views": 12,
    "clicks": 4,
    "bookings": 2,
    "sold": 3
  }
]
```

### 8.9 SSE

#### `GET /api/bookings/{id}/events` JWT，`text/event-stream`

仅订单所有者。Origin 必须在 CORS 白名单（无 Origin 的本机 curl 放行）。连接后立刻推当前状态，之后每次提交后的状态变化再推。心跳 15s 一次，事件名 `heartbeat`。

```
event: booking-status
data: {"bookingId":10,"status":"CONFIRMED"}

event: heartbeat
data: {}
```

SSE 是提示。重连后先 `GET /api/bookings/{id}` 再挂流。推送必须在事务提交之后（`AFTER_COMMIT`），避免回滚状态泄漏。

不使用 WebSocket。图中的 WebSocket/SSE 在本项目落地为 **SSE**。

### 8.10 健康与指标

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/actuator/health` | 公开 | Compose / smoke，期望 `"status":"UP"` |
| GET | `/actuator/prometheus` | 公开 | scrape |
| GET | `/api/meta/metrics` | 主办方 JWT | 给看板的 JSON |

`/api/meta/metrics`：

```json
{
  "bookingsCreated": 12,
  "bookingsCancelled": 2,
  "recommendationsServed": 40,
  "outboxPending": 0
}
```

---

## 9 Kafka 与 Outbox

Topic：`booking-events`。Key：`bookingId` 字符串。

**不要**在 Service 里直接 `KafkaTemplate.send`。预订 / 取消事务插入：

```json
{
  "type": "BOOKING_CREATED",
  "bookingId": 10,
  "eventId": 1,
  "userId": 3,
  "quantity": 2
}
```

`OutboxRelay` 定时取 `published_at IS NULL`，发到 Kafka，成功后填 `published_at`。`type` 为 `BOOKING_CREATED` 或 `BOOKING_CANCELLED`。

Consumer group `eventpulse`：

1. 写 `notifications.message = "Kafka 已处理：" + type`
2. 写 `interactions`：`BOOK` 或 `CANCEL`

这是教学版 Outbox：保证「写库成功则消息最终会发」。不做无间隙序号、gap 检测、DLT、跨服务 Saga 补偿。进程在 relay 之前崩溃，重启后会补发。

---

## 10 ML 预测 / Ranking Model

预测：时刻 `t` 给可售活动打分，近似 P(用户会 CLICK/BOOK)。在线排序；离线用时间切分后的互动当标签。`remaining` 只展示，下单仍走 Inventory。

### 10.1 特征（point-in-time）

| 特征 | 来源 | 截止 |
| --- | --- | --- |
| 类别 / 城市匹配 | `user_preferences` × `events` | `queryAsOf` |
| 距离 | PostGIS `ST_Distance`（有坐标时） | `queryAsOf` |
| 热门度 | Redis `popular:counts`，回源 interactions 或 `sold` | `< queryAsOf` |
| 文本 embedding | `events.embedding <=> userPrefVector` | 活动写入时固化 |
| 近期互动 | 过去 90 天 VIEW/CLICK/BOOK | `< queryAsOf` |

禁止用 `t` 之后的行为解释 `t` 的排序。

### 10.2 在线模型

**V0 `v0-popularity`**：

```
score = 1.0 * popularCount + 2.0 * categoryMatch + 1.0 * cityMatch
```

**V1 `v1-hash-embedding`**：

```
score = V0
      + 4.0 * (1 - cosineDistance)
      + 1.5 * recentPositiveInteract
      + 1.0 * nearbyBoost
```

`nearbyBoost`：`section=nearby` 或距离 < 10 km 为 1，否则 0。reasons：贡献 > 0 才输出 `POPULAR` / `MATCHES_PREFERENCE` / `NEARBY` / `EMBEDDING_MATCH` / `RECENT_INTERACT`。

### 10.3 Hash embedding + pgvector

64 维。token 后 `bucket = floorMod(javaStringHashCode(token), 64)`，`vector[bucket] += 1`，L2 归一化。与 `String.hashCode()` 逐 bit 相同。无 GPU、无 sentence-transformers。

写入 `vector(64)`，在线用 `<=>`。扩展不可用时退回 V0，不要让推荐 500。

### 10.4 离线评估 `ml/`

`ml/ml_eval/evaluate.py`、`ml/tests/test_evaluate.py`、`ml/pyproject.toml`、`ml/uv.lock`。

- 固定种子合成数据；报告必须标注 `SYNTHETIC PIPELINE EVALUATION`
- 按 `occurred_at` 切 80/20
- 热门基线 vs 个性化
- NDCG@10、Recall@10、coverage、diversity、bootstrap 95% CI
- `cd ml && uv sync --frozen && uv run pytest -q`；报告 `uv run python -m ml_eval.evaluate`
- `make test-ml`；`make test-all` 含这一层；CI 增加 ml job

---

## 11 前端

两个表面，一个 SPA：

**Discovery App**：活动、附近、详情、登录、预订、消息、偏好、为你推荐。打开某条预订时挂 `EventSource` 到 `/api/bookings/{id}/events`（fetch 带 Authorization 的 polyfill 或 cookie 方案二选一；**禁止**把 JWT 拼进 URL）。断线后 REST 再拉一次。

**Organiser Console**：`/organiser` 发布改取消；漏斗表；`/metrics` 画 bookingsCreated / recommendationsServed / outboxPending。

ML 交互：

1. 首页「为你推荐」：`GET /api/recommendations?section=for-you&limit=8`，展示 reasons 文案。
2. 详情 `VIEW`；从推荐位进入带 `CLICK` + `position`。
3. `/preferences` 保存后刷新推荐。

未登录推荐走 V0。前端仍读 `Result.data`。

---

## 12 可观测性

Micrometer 计数：`bookings.created`、`bookings.cancelled`、`recommendations.served`、`outbox.pending`（gauge）。

`/actuator/prometheus` 给 scrape。`/api/meta/metrics` + `/metrics` 页就是图中的 Metrics Dashboard。本练习不强制上 Grafana。

日志禁止打印 JWT、密码、embedding 全文。

---

## 13 实施路线

| 周 | 做什么 | 怎么验收 |
| --- | --- | --- |
| 1 | Flyway 业务四表、Entity / Repository、活动与预订 CRUD、`Result` | curl 列出活动、建预订 |
| 2 | 注册登录、JWT 拦截器、主办方发布 | 无 token 打 `/mine` 应 401 |
| 3 | 先直发 Kafka 打通通知页（随后由 Outbox 替换直发） | 预订后「消息」出现 Kafka 文案 |
| 4 | React Discovery + Organiser、Docker Compose、JaCoCo 90%、CI | `make up` + `make test-all` + Actions 绿 |
| 5 | 抽出 Inventory；Outbox + Relay 替换直发 | 杀 backend 再起来，未发的 outbox 会补发 |
| 6 | ML 表 + pgvector embedding + `GET /api/recommendations` V0/V1 | 登录 reasons 含兴趣/向量；扩展缺失时 V0 |
| 7 | preferences / interactions、Kafka 写 BOOK/CANCEL、前端推荐区、`ml/`、CI ml job | `uv run pytest` 绿；冒烟含推荐 |
| 8 | PostGIS 附近、Redis 热门缓存、SSE、漏斗、Metrics Dashboard | 附近按距离；Redis 停仍能列表；SSE 收到状态；`/metrics` 有数字 |

第 1–4 周不改预订事务语义。第 5 周才把「发 Kafka」挪进 Outbox。第 6–7 周才动推荐。第 8 周补齐图上剩余盒子。

---

## 14 测试与 CI

- 后端：Result / JWT / Inventory 条件更新 / Outbox relay / SSE 所有权 / 推荐打分 / embedding 哈希；JaCoCo 排除启动类，行覆盖率 90%。
- 前端：Vitest 80%；Playwright 测 Discovery 标题、登录、推荐分区、Organiser 入口（不依赖后端）。
- ML：`uv run pytest`；lockfile `--frozen`。
- 冒烟：预订 → 消息；登录后 `GET /api/recommendations` 含 `modelVersion`；`GET /api/meta/metrics` 200。
- CI：gitleaks、`mvn verify`、frontend lint + coverage + build + e2e、ml pytest。

Compose：`postgres`（PostGIS + pgvector）、`redis`、`kafka`、`backend`、`frontend`。

---

## 15 诚实边界

单节点 Kafka / Redis。演示账号。Outbox 保证最终发出，不保证恰好一次业务副作用（消费者要能重复写通知——用 bookingId+type 去重即可）。SSE 会断，REST 才是事实。推荐是合成评估，不是点击率承诺。附近依赖主办方填写坐标。这是练习项目：把图上的盒子用能讲清的实现跑通，不是生产订票或生产推荐系统。
