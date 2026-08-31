# EventPulse 开发计划（简化版）

Spring Boot 4 · Kafka · PostgreSQL · 给初学者的 CRUD 练习。分层对齐 firmament：`Controller → Service → Repository`。已删除 RateLimiter、DbClock、Outbox、钱包、推荐、Redis。

Kaiwen Yao · 2026-08-31

## 1 目标

用最少代码讲清三件事：

1. **HTTP CRUD**：注册登录、活动、预订
2. **鉴权**：JWT 拦截器，不用 Spring Security 过滤器链
3. **消息**：预订写库后发 Kafka，消费者写成通知

成功标准：本机 `make up` 能走通预订 → 消息；`mvn verify` 行覆盖率 ≥ 90%；frontend Vitest ≥ 80%；CI 在 `main` 全绿。

非目标：真实支付、超卖锁协议、Outbox、推荐、生产级运维。

## 2 角色与页面

| 角色 | 页面 | 能做什么 |
| --- | --- | --- |
| 访客 | 活动列表 / 详情 / 登录注册 | 浏览 |
| USER | 预订、我的预订、消息 | 预订与取消 |
| ORGANISER | 以上 + 主办方 | 发布 / 改 / 取消自己的活动 |

注册接口不接受 `role`，一律写成 `USER`。演示主办方由 `demo` profile 播种。

## 3 分层（对照 firmament）

```
Controller  → 收请求，返回 Result<T>
Service     → 业务规则（余票、所有权、取消）
Repository  → Spring Data JPA
Entity      → 四张表
Interceptor → JWT，公开路径白名单
kafka       → Producer 发、Consumer 写 notifications
```

统一返回：`{"code":1,"msg":null,"data":...}` 成功；业务错误 HTTP 400 + `code=0`。

## 4 数据模型

四张表，见 `backend/src/main/resources/db/migration/V1__init.sql`。

| 表 | 关键字段 |
| --- | --- |
| users | email, password (BCrypt), name, role (`USER` / `ORGANISER`) |
| events | title, category, city, starts_at, price_cents, capacity, sold, organiser_id, status |
| bookings | user_id, event_id, quantity, status (`CONFIRMED` / `CANCELLED`) |
| notifications | booking_id, message（由 Kafka 消费者写入） |

活动状态：`PUBLISHED` / `CANCELLED`。预订成功后 `sold += quantity`，取消后减回去。余票不足拒绝。

## 5 Kafka

Topic：`booking-events`。

Producer 在创建 / 取消预订成功后发：

```json
{"type":"BOOKING_CREATED","bookingId":1,"eventId":2,"userId":3,"quantity":1}
```

Consumer `@KafkaListener` 写成 `Kafka 已处理：BOOKING_CREATED`。这是教学用直发，不是 Outbox；进程在「写库成功、消息未发出」之间崩溃会丢通知。

## 6 鉴权

`JwtService` 签发 HS256，claims 为 `userId` + `role`。`JwtInterceptor` 公开：

- `OPTIONS`
- `POST /api/auth/login`、`POST /api/auth/register`
- `GET /api/events`、`GET /api/events/{数字id}`

`GET /api/events/mine` 必须带 JWT。主办方写接口在 Service 里再校验 `role == ORGANISER` 且 `organiserId` 匹配。

## 7 四周路线

| 周 | 做什么 | 怎么验收 |
| --- | --- | --- |
| 1 | Flyway 四表、Entity / Repository、活动与预订 CRUD、`Result` | 用 curl 列出活动、建预订 |
| 2 | 注册登录、JWT 拦截器、主办方发布 | 无 token 打 `/mine` 应 401 |
| 3 | `BookingProducer` / `BookingConsumer`、消息页 | 预订后「消息」出现 Kafka 文案 |
| 4 | React 单页、Docker Compose、JaCoCo 90%、CI | `make up` + `make test-all` + Actions 绿 |

## 8 测试与 CI

- 后端：`UnitTest` 覆盖 Result / JWT / Service / Kafka / 拦截器 / Controller；JaCoCo 排除启动类，行覆盖率门禁 90%
- 前端：Vitest 80%；Playwright 只测 SPA 自己能渲染的标题和登录表单（不依赖后端）
- CI：gitleaks、`mvn verify`、frontend lint + coverage + build + e2e

## 9 诚实边界

单节点 Kafka、演示账号、通知可能因进程崩溃丢失。这是练习项目，不是生产订票系统。
