# EventPulse 开发计划

Spring Boot 4 · Kafka · PostgreSQL · Python ML。分层对齐 firmament：`Controller → Service → Repository`。统一 `Result`，JWT 拦截器鉴权，预订走 Kafka 通知，**用 ML 预测用户会对哪些活动感兴趣**。

不引入 RateLimiter、DbClock、Outbox、钱包、Redis、PostGIS、真实支付。推荐只做预测与解释，不当交易或库存的事实源。

Kaiwen Yao · 2026-08-31

## 目录

1. [目标、成功标准与边界](#1-目标成功标准与边界)
2. [角色、流程与页面](#2-角色流程与页面)
3. [分层](#3-分层)
4. [数据模型](#4-数据模型)
5. [鉴权](#5-鉴权)
6. [统一响应与错误](#6-统一响应与错误)
7. [HTTP API（完整清单）](#7-http-api完整清单)
8. [Kafka](#8-kafka)
9. [ML 预测](#9-ml-预测)
10. [前端](#10-前端)
11. [实施路线](#11-实施路线)
12. [测试与 CI](#12-测试与-ci)
13. [诚实边界](#13-诚实边界)

---

## 1 目标、成功标准与边界

用最少代码讲清四件事：

1. **HTTP CRUD**：注册登录、活动、预订
2. **鉴权**：JWT 拦截器，不用 Spring Security 过滤器链
3. **消息**：预订写库后发 Kafka，消费者写成通知
4. **ML 预测**：根据偏好、浏览/预订行为和热门度，预测用户会互动的活动；离线用时间切分评估，报告标注 SYNTHETIC

| 维度 | 门槛 | 证据 |
| --- | --- | --- |
| CRUD + 鉴权 | `make up` 能注册、浏览、预订、取消；无 token 打需登录接口返回 401 | smoke + 拦截器单测 |
| 消息 | 预订 / 取消后「消息」页出现 `Kafka 已处理：BOOKING_*` | smoke + Kafka 单测 |
| 推荐效果 | V1 相对热门基线有离线对照，且没有未来泄漏 | `ml/` 时间切分、NDCG@10、Recall@10、coverage、diversity、bootstrap CI |
| 质量 | 后端 JaCoCo 行覆盖率 ≥ 90%；前端 Vitest ≥ 80%；CI 在 `main` 全绿 | `mvn verify`、Vitest、`uv run pytest` |

非目标：真实支付、超卖锁协议、Outbox、生产级运维、宣称合成数据代表真实商业效果。推荐不得改 `sold` / 余票 / 订单状态。

---

## 2 角色、流程与页面

| 角色 | 页面 | 能做什么 |
| --- | --- | --- |
| 访客 | 活动列表 / 详情 / 登录注册 / 热门推荐 | 浏览；推荐走 V0 热门，不读个人偏好 |
| USER | 以上 + 预订、我的预订、消息、偏好、为你推荐 | 预订与取消；写互动；拿个性化预测 |
| ORGANISER | 以上 + 主办方 | 发布 / 改 / 取消自己的活动 |
| 推荐系统 | `ml/` 离线评估 + 在线 `/api/recommendations` | 只输出排序与理由，不成为库存或权限事实源 |

注册接口不接受 `role`，一律写成 `USER`。演示主办方由 `demo` profile 播种。

普通用户主流程：

1. 注册 / 登录，拿到 JWT。
2. （可选）写入兴趣类别和常驻城市。
3. 浏览列表或「为你推荐」；点进详情时上报 `VIEW` / `CLICK`。
4. 预订成功 → 写 `bookings` → Kafka `BOOKING_CREATED` → 通知 + 一条 `BOOK` 互动。
5. 取消同理，发 `BOOKING_CANCELLED` 并记 `CANCEL`。

前端路由：

| 路径 | 页面 | 鉴权 |
| --- | --- | --- |
| `/` | 发现：搜索 + 列表 + 推荐分区 | 公开 |
| `/events/:id` | 活动详情 / 预订 | 浏览公开，预订需登录 |
| `/login` | 登录 / 注册 | 公开 |
| `/preferences` | 兴趣类别、常驻城市 | 登录 |
| `/bookings` | 我的预订 / 取消 | 登录 |
| `/notifications` | Kafka 写入的消息 | 登录 |
| `/organiser` | 发布 / 修改 / 取消自己的活动 | 主办方 |

演示账号（`demo` profile）：

| 角色 | 邮箱 | 密码 |
| --- | --- | --- |
| USER | `user@eventpulse.dev` | `User123456` |
| ORGANISER | `organiser@eventpulse.dev` | `Organiser123456` |

---

## 3 分层

```
Controller  → 收请求，返回 Result<T>；不写业务规则
Service     → 余票、所有权、取消、推荐打分
Repository  → Spring Data JPA
Entity      → 表行；不直接作为 HTTP 响应
dto         → *Request 入参，*Vo 出参（Java record，不单独建 vo 包）
Interceptor → JWT；公开路径白名单；可选 token 的接口要解析但不强制
kafka       → Producer 发预订事件，Consumer 写 notifications 和 BOOK/CANCEL 互动
ml/         → 离线评估，与后端共用同一套 hash embedding 定义
```

VO 放在 `dto` 包里，例如 `EventDtos.EventVo`。Entity 不出接口：`User.password` 不得出现在 `UserVo`；`EventVo.remaining` 是 `capacity - sold`，不是列。

---

## 4 数据模型

业务四张表见 `backend/src/main/resources/db/migration/V1__init.sql`。ML 用 Flyway 后续版本加表，不改已有列的含义。

### 4.1 业务表

| 表 | 关键字段 |
| --- | --- |
| users | email UNIQUE, password (BCrypt), name, role (`USER` / `ORGANISER`) |
| events | title, description, category, city, starts_at, price_cents, capacity, sold, organiser_id, status, created_at |
| bookings | user_id, event_id, quantity, status (`CONFIRMED` / `CANCELLED`), created_at |
| notifications | booking_id, message（由 Kafka 消费者写入）, created_at |

活动状态：`PUBLISHED` / `CANCELLED`。预订成功 `sold += quantity`，取消减回去，余票不足拒绝。容量不得小于已售。

类别约定（推荐和偏好共用）：`music` / `tech` / `sports` / `art` / `food`。

### 4.2 ML 表

| 表 | 关键字段 | 用途 |
| --- | --- | --- |
| user_preferences | user_id PK → users, categories `TEXT[]`, city | 显式兴趣；注册时可不填 |
| interactions | id, user_id NULL（访客）, event_id, type, position, occurred_at | 行为日志；训练与在线特征只读发生时刻之前的行 |
| recommendation_requests | id, user_id NULL, section, model_version, feature_version, candidate_ids JSON, query_as_of, expires_at | 冻结本轮候选，翻页 cursor 指向这一行，避免翻页时排序跳变 |

`events` 增加 `embedding TEXT`：64 维 hash 向量的字面量，例如 `[0.01,-0.02,...]`。发布 / 更新活动时由 `EmbeddingService` 写入。不依赖 pgvector 扩展。

`interactions.type`：`VIEW` / `CLICK` / `SAVE` / `UNSAVE` / `BOOK` / `CANCEL`。`BOOK` / `CANCEL` 只由 Kafka 消费者写，客户端不能伪造。

不变量：推荐读写不得改变 `events.sold` 和 `bookings.status`。特征必须带 `occurred_at` / `query_as_of`，禁止用「现在之后」的互动打「当时」的分。

---

## 5 鉴权

`JwtService` 签发 HS256，claims：`userId`、`role`。Header：`Authorization: Bearer <token>`。

`JwtInterceptor` 拦截 `/api/**`。公开路径（不强制登录）：

- 所有 `OPTIONS`
- `POST /api/auth/login`、`POST /api/auth/register`
- `GET /api/events`、`GET /api/events/{数字id}`
- `GET /api/recommendations`
- `GET /actuator/health`

`GET /api/recommendations` 是**可选 JWT**：有合法 token 则写入 `BaseContext` 做个性化；没有 token 走访客热门，不要 401。实现上公开路径若带 `Bearer` 仍应尝试 parse。

其余 `/api/**` 无 token 或 token 无效 → HTTP 401，`{"code":0,"msg":"未登录或 token 无效"}`。

`GET /api/events/mine` 以及活动写接口：Service 再校验 `role == ORGANISER` 且 `organiserId` 匹配。对象不存在与无权访问对调用方都表现为业务错误（不泄露别人的资源）。

注册 DTO 不接受 `role` / `status` / `organiserId`。

---

## 6 统一响应与错误

成功 HTTP 200：

```json
{ "code": 1, "msg": null, "data": { } }
```

无 body 的成功（例如取消活动）`data` 为 `null`。

业务错误 HTTP 400：

```json
{ "code": 0, "msg": "余票不足", "data": null }
```

校验失败 HTTP 400，`msg` 为第一个字段错误，例如 `"quantity 必须大于等于 1"`。

未登录 HTTP 401（拦截器直接写 JSON，不走 `Result.success`）。

未捕获异常 HTTP 500，`msg` 为异常信息或 `"服务器错误"`。

时间一律 ISO-8601 UTC（`Instant`）。金额一律整数分 `priceCents`。

---

## 7 HTTP API（完整清单）

基址：`http://localhost:8080`。Compose 前端在 `http://localhost:3000`，`/api` 反代到 backend。

### 7.1 认证 `/api/auth`

#### `POST /api/auth/register` 公开

请求：

```json
{ "email": "a@b.com", "password": "secret1", "name": "Kaiwen" }
```

| 字段 | 约束 |
| --- | --- |
| email | 非空、合法邮箱 |
| password | 非空、6–64 |
| name | 非空、最长 50 |

成功 `data`：

```json
{
  "token": "<jwt>",
  "user": { "id": 1, "email": "a@b.com", "name": "Kaiwen", "role": "USER" }
}
```

失败：邮箱已注册 → `"邮箱已被注册"`。role 固定 `USER`。

#### `POST /api/auth/login` 公开

请求：`{ "email", "password" }`。成功同 register。失败：`"邮箱或密码错误"`（不区分用户是否存在）。

#### `GET /api/auth/me` JWT

成功 `data`：`{ "id", "email", "name", "role" }`。不含 password、不含 token。

### 7.2 活动 `/api/events`

`EventVo`：

```json
{
  "id": 1,
  "title": "城市脉搏 · 独立摇滚之夜",
  "description": "演示活动，可直接预订。",
  "category": "music",
  "city": "上海",
  "startsAt": "2026-09-14T12:00:00Z",
  "priceCents": 18000,
  "capacity": 300,
  "sold": 2,
  "remaining": 298,
  "organiserId": 2,
  "status": "PUBLISHED"
}
```

`EventRequest`（创建 / 更新）：

```json
{
  "title": "城市脉搏 · 独立摇滚之夜",
  "description": "可选",
  "category": "music",
  "city": "上海",
  "startsAt": "2026-09-14T12:00:00Z",
  "priceCents": 18000,
  "capacity": 300
}
```

| 字段 | 约束 |
| --- | --- |
| title | 非空、最长 200 |
| category / city | 非空、最长 50 |
| startsAt | 非空 |
| priceCents | ≥ 0 |
| capacity | ≥ 1；更新时不得小于当前 sold |

#### `GET /api/events` 公开

查询参数（均可省略）：`city`、`category`、`q`（标题子串，大小写不敏感）。只返回 `status=PUBLISHED`，按 `startsAt` 升序。成功 `data` 为 `EventVo[]`。

#### `GET /api/events/{id}` 公开

`id` 必须是数字（否则不走这条公开白名单）。不存在 → `"活动不存在"`。取消后的活动仍可按 id 查看，前端用 `status` 禁止预订。

#### `GET /api/events/mine` 主办方 JWT

当前用户作为主办方发布的全部活动（含已取消），按 `startsAt` 降序。非主办方 → `"只有主办方可以管理活动"`。

#### `POST /api/events` 主办方 JWT

`sold=0`，`status=PUBLISHED`，`organiserId=当前用户`。写入后计算并保存 `embedding`。成功返回新 `EventVo`。

#### `PUT /api/events/{id}` 主办方 JWT

只能改自己的活动。成功返回更新后的 `EventVo`，并重算 embedding。

#### `DELETE /api/events/{id}` 主办方 JWT

把 `status` 设为 `CANCELLED`，不删行。成功 `data=null`。已取消的活动不可再预订。

### 7.3 预订 `/api/bookings`

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

请求：`{ "eventId": 1, "quantity": 2 }`。`quantity` 1–10。

成功：写 `bookings`（`CONFIRMED`），`sold += quantity`，发 Kafka `BOOKING_CREATED`，返回 `BookingVo`。

失败：

| 条件 | msg |
| --- | --- |
| 活动不存在 | 活动不存在 |
| 活动已取消 | 活动已取消，无法预订 |
| sold + quantity > capacity | 余票不足 |
| 未登录 | 请先登录 / 拦截器 401 |

本练习不做支付、不超卖锁、不幂等键。同一用户可对同一活动多次预订。

#### `GET /api/bookings` JWT

当前用户全部预订，按 `createdAt` 降序。`data` 为 `BookingVo[]`。

#### `GET /api/bookings/{id}` JWT

只能看自己的。别人的或不存在 → `"订单不存在"` 或 `"只能查看自己的订单"`。

#### `POST /api/bookings/{id}/cancel` JWT

仅 `CONFIRMED` 可取消。`sold` 减回（不低于 0），状态 `CANCELLED`，发 Kafka `BOOKING_CANCELLED`，返回更新后的 `BookingVo`。已取消再取消 → `"订单已取消"`。

### 7.4 通知 `/api/notifications`

#### `GET /api/notifications` JWT

当前用户所有预订关联的通知，按 `createdAt` 降序。

`NotificationVo`：`{ "id", "bookingId", "message", "createdAt" }`。

`message` 形如 `Kafka 已处理：BOOKING_CREATED`。这是教学用直发，不是 Outbox。

### 7.5 偏好 `/api/preferences`

#### `GET /api/preferences` JWT

无记录时返回空偏好，不要 404：

```json
{ "categories": [], "city": null }
```

#### `PUT /api/preferences` JWT

请求：

```json
{ "categories": ["music", "tech"], "city": "上海" }
```

`categories` 必须是约定类别的子集，可空。`city` 可 null。upsert 后原样返回。这是显式特征，推荐读取它，但不强制用户填写。

### 7.6 互动 `/api/interactions`

#### `POST /api/interactions` JWT

必须登录。请求：

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
| position | 可空；推荐列表中的 0-based 下标 |
| events | 1–50 条 |

成功：`{ "accepted": 2 }`。忽略未知 `eventId`（不报 400，避免前端因过期列表失败）。禁止客户端传 `BOOK` / `CANCEL`。

### 7.7 推荐（预测）`/api/recommendations`

#### `GET /api/recommendations` 可选 JWT

查询参数：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| section | `for-you` | `for-you` 个性化；`popular` 只按热门；`nearby` 用偏好 city，无偏好则退回 popular |
| limit | 10 | 1–50，本页条数 |
| cursor | 空 | 上一页返回的 `nextCursor`；带 cursor 时忽略 section/limit 的变更，使用冻结候选 |

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

`nextCursor` 无下一页时为 `null`。

规则：

- 只召回 `PUBLISHED` 且 `startsAt > queryAsOf` 的活动。
- 第一次请求写入 `recommendation_requests`，冻结 `candidate_ids`；`nextCursor` 签到这一行 + offset。cursor 过期或不存在 → 业务错误 `"推荐结果已过期，请重新刷新"`。
- 访客：`modelVersion=v0-popularity`，reasons 主要是 `POPULAR`。
- 登录且 embedding 可用：`v1-hash-embedding`。
- `score` 仅排序用，不做 API 稳定性承诺；`reasons` 必须能从本次用到的特征对上，禁止编造。

### 7.8 健康检查

`GET /actuator/health` 公开。Compose / smoke 期望 `"status":"UP"`。

---

## 8 Kafka

Topic：`booking-events`。Key：`bookingId` 字符串。

Producer 在创建 / 取消预订**写库成功后**发送：

```json
{
  "type": "BOOKING_CREATED",
  "bookingId": 10,
  "eventId": 1,
  "userId": 3,
  "quantity": 2
}
```

`type` 为 `BOOKING_CREATED` 或 `BOOKING_CANCELLED`。

Consumer group `eventpulse` 做两件事（同一条消息）：

1. 写 `notifications.message = "Kafka 已处理：" + type`
2. 写 `interactions`：`BOOKING_CREATED` → type `BOOK`；`BOOKING_CANCELLED` → type `CANCEL`

这是教学用直发，不是 Outbox。进程在「写库成功、消息未发出」之间崩溃会丢通知和对应互动；计划接受这一点。

---

## 9 ML 预测

预测问题：给定用户（或访客）在时刻 `t` 的可见信息，给每个可售活动一个分数，近似 **P(用户会在未来对它 CLICK/BOOK)**。在线用分数排序；离线用时间切分后的真实互动当标签。

推荐不是库存服务：`remaining` 只展示，下单仍走预订接口重算 `sold`。

### 9.1 特征（point-in-time）

| 特征 | 来源 | 截止时刻 |
| --- | --- | --- |
| 类别 / 城市匹配 | `user_preferences` × `events` | `queryAsOf` |
| 热门度 | `interactions` 中 BOOK+CLICK 计数，或回退 `events.sold` | `< queryAsOf` |
| 文本 embedding | `events.embedding` 与用户偏好文本的余弦 | 活动写入时固化 |
| 近期互动 | 用户过去 90 天 VIEW/CLICK/BOOK | `< queryAsOf` |

禁止用 `t` 之后的预订或点击来解释 `t` 时刻的排序。

### 9.2 在线模型

**V0 `v0-popularity`**（访客，或 embedding 未就绪）：

```
score = 1.0 * popularCount + 2.0 * categoryMatch + 1.0 * cityMatch
```

**V1 `v1-hash-embedding`**（登录且活动已有向量）：

```
score = V0
      + 4.0 * cosine(userPrefVector, event.embedding)
      + 1.5 * recentPositiveInteract
```

`userPrefVector`：把用户 `categories` 与 city 拼成文本再 embed。reasons：对应项贡献 > 0 才输出 `POPULAR` / `MATCHES_PREFERENCE` / `NEARBY` / `EMBEDDING_MATCH` / `RECENT_INTERACT`。

### 9.3 Hash embedding（后端与 Python 必须一致）

64 维。对文本按空白和标点切 token，每个 token：

```
bucket = floorMod(javaStringHashCode(token), 64)
vector[bucket] += 1
```

再 L2 归一化。`javaStringHashCode` 与 `String.hashCode()` 逐 bit 相同（Python 侧手写同一循环）。无 GPU、无 sentence-transformers、无 pgvector。活动 create/update 时写入 `events.embedding`。

### 9.4 离线评估 `ml/`

目录：`ml/ml_eval/evaluate.py`、`ml/tests/test_evaluate.py`、`ml/pyproject.toml`、`ml/uv.lock`。

- 合成数据：固定种子，约 1 万用户 / 5 千活动 / 50 万条互动；**报告必须标注 `SYNTHETIC PIPELINE EVALUATION`**。
- 按 `occurred_at` 切 80/20，训练不得看见测试段。
- 对照：热门基线 vs 个性化（类别亲和 + embedding）。
- 指标：NDCG@10、Recall@10、coverage、diversity、bootstrap 95% CI。
- 命令：`cd ml && uv sync --frozen && uv run pytest -q`；完整报告 `uv run python -m ml_eval.evaluate`。
- Makefile：`make test-ml`；`make test-all` 含这一层。CI 增加 ml job。

合成指标只证明管道可复现，不证明真实增长。

---

## 10 前端

现有单页：活动 / 详情 / 登录 / 预订 / 消息 / 主办方。ML 补三处，不加新框架：

1. 首页顶部「为你推荐」：调 `GET /api/recommendations?section=for-you&limit=8`，卡片展示 `reasons` 文案（`MATCHES_PREFERENCE` → 「符合你的兴趣」等）。
2. 详情进入时 `POST /api/interactions` 一条 `VIEW`；从推荐位点入带 `position` 和 `CLICK`。
3. `/preferences`：多选类别 + 城市，保存后刷新推荐。

未登录推荐区仍渲染，走 V0。前端继续用 `Result.data`，`code===0` 当错误。

---

## 11 实施路线

| 周 | 做什么 | 怎么验收 |
| --- | --- | --- |
| 1 | Flyway 业务四表、Entity / Repository、活动与预订 CRUD、`Result` | curl 列出活动、建预订 |
| 2 | 注册登录、JWT 拦截器、主办方发布 | 无 token 打 `/mine` 应 401 |
| 3 | `BookingProducer` / `BookingConsumer`、消息页 | 预订后「消息」出现 Kafka 文案 |
| 4 | React 单页、Docker Compose、JaCoCo 90%、CI | `make up` + `make test-all` + Actions 绿 |
| 5 | ML 表 + embedding + `GET /api/recommendations` V0/V1、冻结 cursor | 登录后 reasons 含兴趣/向量；访客只有热门；过期 cursor 报错 |
| 6 | `preferences` / `interactions`、Kafka 写 BOOK/CANCEL、前端推荐区、`ml/` 离线报告、CI ml job | `uv run pytest` 绿；报告有 SYNTHETIC 标注；冒烟含推荐接口 |

第 1–4 周是主干，不得为 ML 改预订事务语义。第 5–6 周才动推荐相关表和拦截器的可选 JWT。

---

## 12 测试与 CI

- 后端：`UnitTest` 覆盖 Result / JWT / Service / Kafka / 拦截器 / Controller；推荐打分与 embedding 哈希；JaCoCo 排除启动类，行覆盖率门禁 90%。
- 前端：Vitest 80%；Playwright 测 SPA 标题、登录表单、推荐分区能渲染（不依赖后端）。
- ML：`uv run pytest`（合成数据、时间切分、指标烟雾）；lockfile `--frozen`。
- 冒烟：现有预订 → 消息之外，增加登录后 `GET /api/recommendations` 返回 `items` 且含 `modelVersion`。
- CI：gitleaks、`mvn verify`、frontend lint + coverage + build + e2e、`ml` pytest。

---

## 13 诚实边界

单节点 Kafka、演示账号、通知和 BOOK 互动可能因进程崩溃丢失。预订没有支付、没有分布式锁、没有 Outbox。推荐用合成评估，分数不是点击率承诺。这是练习项目：把 CRUD、消息和可复现的预测管线跑通，不是生产订票或生产推荐系统。
