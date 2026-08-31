# EventPulse

给初学者的活动预订练习项目：**Spring Boot CRUD + Kafka 通知**。分层对齐 firmament：`Controller → Service → Repository (JPA) → Entity`，统一 `Result`（`code=1` 成功 / `code=0` 失败），JWT 拦截器鉴权。

已删除 RateLimiter、DbClock、Outbox、钱包、推荐、Redis、PostGIS、pgvector。

React 19 · Spring Boot 4.1.1 · PostgreSQL 18 · Apache Kafka 4.3.1（KRaft）· Docker Compose 一键启动。

## 学什么

1. 四张表的基本 CRUD：`users`、`events`、`bookings`、`notifications`
2. 预订成功后往 Kafka topic `booking-events` 发 JSON
3. `@KafkaListener` 消费后写成一条通知，前端「消息」页能看到

```
用户预订 → BookingService 写 bookings → BookingProducer 发 Kafka
                                           ↓
                              BookingConsumer 写 notifications
```

## 快速开始（Docker Compose）

本机只需要 **Docker Desktop（macOS / Windows）或 Docker Engine + Compose v2（Linux）**。

### 1. 端口

| 端口 | 服务 | 说明 |
| --- | --- | --- |
| 3000 | frontend | SPA；`/api` 反代到 backend |
| 8080 | backend | Spring Boot API |
| 5432 | postgres | PostgreSQL 18 |
| 9092 | kafka | 对外广告 `127.0.0.1:9092` |

### 2. 配置

```bash
cp .env.example .env
```

demo 可直接用默认值。`SECRET_KEY` 用于 JWT 签名；`DB_PASSWORD` 是 Postgres 密码。

### 3. 启动

```bash
make up          # docker compose up -d --build
make ps          # postgres / kafka / backend / frontend 均应 healthy
```

第一次会构建 backend / frontend 镜像，大约几分钟。backend 健康检查有 60s `start_period`（Flyway + demo seed）。

```bash
curl -s http://localhost:8080/actuator/health
# 期望：{"status":"UP", ...}
```

打开前端：**http://localhost:3000**。同一套 API 也可直接打 `http://localhost:8080`。

### 4. 演示账号

`demo` profile 启动时播种 2 个账号和 4 个活动：

| 角色 | 邮箱 | 密码 | 能做什么 |
| --- | --- | --- | --- |
| 普通用户 | `user@eventpulse.dev` | `User123456` | 浏览、预订、取消、看消息 |
| 主办方 | `organiser@eventpulse.dev` | `Organiser123456` | 以上 + 发布 / 修改 / 取消自己的活动 |

建议最短路径：普通用户登录 → 点进任一活动 → 预订 → 「消息」页看到 `Kafka 已处理：BOOKING_CREATED`。

### 5. 冒烟

栈 healthy 之后需要本机 `curl` 和 `python3`：

```bash
make smoke                 # 默认打 http://localhost:8080
# BASE_URL=http://localhost:3000/api  bash scripts/smoke-test.sh
```

全部 PASS 会打印 `SMOKE TEST: ALL GREEN`。

### 6. 停栈

```bash
make logs                  # 跟随 backend 日志
make down                  # 停容器，保留数据卷
make down-v                # 停容器并删数据卷；下次 make up 会重新 seed
```

## 本地开发

```bash
make infra                 # 只启动 postgres / kafka
# 终端 1
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=demo
# 终端 2
cd frontend && npm ci && npm run dev
open http://localhost:5173
```

不要同时 `make up` 和本机 `spring-boot:run`：两边都会占 8080。

## 测试

```bash
make test                  # 后端 mvn verify（JaCoCo 行覆盖率 90%）
make test-frontend         # ESLint + Vitest coverage（80%）+ Playwright
make test-all              # 上面两层
```

CI 在 `.github/workflows/ci.yml`：gitleaks、backend `mvn verify`、frontend lint + coverage + build + e2e。

## API（统一包在 `Result` 里）

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | 公开 | 注册，角色固定为 `USER` |
| POST | `/api/auth/login` | 公开 | 登录，返回 JWT |
| GET | `/api/auth/me` | JWT | 当前用户 |
| GET | `/api/events` | 公开 | 列表，可选 `city` / `category` / `q` |
| GET | `/api/events/{id}` | 公开 | 详情（`id` 必须是数字） |
| GET | `/api/events/mine` | 主办方 JWT | 我发布的活动 |
| POST | `/api/events` | 主办方 JWT | 发布 |
| PUT | `/api/events/{id}` | 主办方 JWT | 修改自己的活动 |
| DELETE | `/api/events/{id}` | 主办方 JWT | 取消活动 |
| POST | `/api/bookings` | JWT | 预订 `{eventId, quantity}` |
| GET | `/api/bookings` | JWT | 我的预订 |
| GET | `/api/bookings/{id}` | JWT | 预订详情 |
| POST | `/api/bookings/{id}/cancel` | JWT | 取消预订 |
| GET | `/api/notifications` | JWT | Kafka 消费后写入的通知 |

Header：`Authorization: Bearer <token>`。

## 仓库结构

```
backend/     Controller / Service / Repository / Entity / kafka
frontend/    单文件 App.tsx：活动 / 详情 / 登录 / 预订 / 消息 / 主办方
deploy/      backend 与 frontend 的 Dockerfile
scripts/     smoke-test.sh
```

更细的学习路线见 [`eventpulse-detailed-project-plan.md`](eventpulse-detailed-project-plan.md)。
