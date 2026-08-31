# EventPulse

给初学者的活动预订练习项目：Spring Boot CRUD + Kafka 通知。风格对齐 firmament，已删除 RateLimiter、DbClock、Outbox、钱包和推荐。

React 19 · Spring Boot 4.1.1（模块化单体）· PostgreSQL 18 + PostGIS 3.6 + pgvector ·
Apache Kafka 4.3.1（KRaft）· Redis 8.2.9 · Docker Compose 一键启动。

## 快速开始（Docker Compose）

不装 JDK / Node / Maven 也能把整套 demo 栈拉起来：Postgres、Kafka、Redis、后端 API、前端（nginx 反代 `/api`）。本机只需要 **Docker Desktop（macOS / Windows）或 Docker Engine + Compose v2（Linux）**。

### 1. 端口与磁盘

首次 `make up` 会构建三个镜像（Postgres 要从源码编 pgvector，backend 跑 Maven，frontend 跑 `npm ci`），大约 **5–15 分钟**，视网络和 CPU 而定。之后再启动会复用镜像，几十秒即可。

请确保下列端口空闲（被占用时对应容器起不来）：

| 端口 | 服务 | 说明 |
| --- | --- | --- |
| 3000 | frontend | SPA；`/api` 和 `/actuator` 反代到 backend |
| 8080 | backend | Spring Boot API |
| 5432 | postgres | PostgreSQL 18 + PostGIS + pgvector |
| 6379 | redis | 缓存 |
| 9092 | kafka | 对外广告 `127.0.0.1:9092`（避免 macOS 上 `localhost` 走 IPv6） |

### 2. 配置环境变量

```bash
cp .env.example .env
```

demo 可以直接用 `.env.example` 里的默认值，不必改。各变量含义：

| 变量 | 默认 | 作用 |
| --- | --- | --- |
| `SECRET_KEY` | 已填开发默认 | JWT 签名；轮换会使已签发 token 失效 |
| `TOKEN_PEPPER` | 已填开发默认 | 票券 token 哈希 pepper |
| `DB_PASSWORD` | `eventpulse` | Postgres 密码 |
| `CORS_ORIGINS` | `http://localhost:3000,http://localhost:5173` | 允许的前端 Origin |

prod profile 检测到默认密钥会拒绝启动。demo 栈走的是 `SPRING_PROFILES_ACTIVE=demo`，不受此限制。

### 3. 启动

仓库根目录：

```bash
make up          # 等价于 docker compose up -d --build
make ps          # 五个服务都应是 healthy：postgres / kafka / redis / backend / frontend
```

backend 的健康检查有 60s `start_period`（Flyway 迁移 + demo seed），第一次起来可能要等一两分钟。若 `make ps` 里 backend 还是 `health: starting`，再等一会儿或 `make logs` 跟日志。

确认 API 已就绪：

```bash
curl -s http://localhost:8080/actuator/health
# 期望：{"status":"UP", ...}
```

然后打开前端：**http://localhost:3000**

也可以直接打 backend：**http://localhost:8080**（无 UI，只有 API）。经 nginx 反代时，同一套 API 在 `http://localhost:3000/api/...`。

### 4. 登录演示账号

demo profile 启动时会幂等播种 3 个账号和 4 个已发布活动。账号：

| 角色 | 邮箱 | 密码 | 登录后能看到 |
| --- | --- | --- | --- |
| 普通用户 | `user@eventpulse.dev` | `User!234567890` | 发现、下单、支付、我的订单、票券二维码 |
| 主办方 | `organiser@eventpulse.dev` | `Organiser!234567890` | 以上 + 主办方后台、现场核销 |
| 管理员 | `admin@eventpulse.dev` | `Admin!234567890` | 以上 + 管理异常视图 |

种子活动（均可购票，限购 10 张/人）：

| 活动 | 类别 | 城市 | 票档（容量） |
| --- | --- | --- | --- |
| 城市脉搏 · 独立摇滚之夜 | music | 上海 | 普通票 ¥180（300）/ VIP票 ¥380（50） |
| AI 与城市生活 · 技术沙龙 | tech | 上海 | 早鸟票 ¥49（120）/ 现场票 ¥99（80） |
| 滨江晨跑 5K | sports | 上海 | 免费票（200） |
| 城市光影 · 数字艺术展 | art | 北京 | 平日票 ¥88（500）/ 双人票 ¥158（100） |

建议走一遍的最短路径：用普通用户登录 → 发现页点进任一活动 → 选票档并创建预订 → 结算页用钱包余额支付（立即出票） → 「我的订单」查看票券。主办方登录后可到「核销」页扫同一张票两次，第二次应被拒绝。更完整的 8 分钟演示见 [`docs/demo-script.md`](docs/demo-script.md)。

### 5. 可选：API 冒烟

栈 healthy 之后，不装 JDK 也能跑全链路 HTTP 断言（注册 → 搜索 → 预订幂等 → 支付出票 → 核销 → 双扫拒绝 → 支付前取消）。需要本机 `curl` 和 `python3`：

```bash
make smoke                 # 默认打 http://localhost:8080
# 经 nginx 反代：
# BASE_URL=http://localhost:3000/api  bash scripts/smoke-test.sh
```

全部 PASS 会打印 `SMOKE TEST: ALL GREEN`。

### 6. 停栈与清数据

```bash
make logs                  # 跟随 backend 日志；Ctrl-C 退出，栈继续跑
make down                  # 停容器，保留 Postgres 数据卷（账号和订单还在）
make down-v                # 停容器并删除数据卷；下次 make up 会重新 seed
```

seed 是幂等的：已有演示用户就不会重插。如果首页没有那 4 个活动、或登录密码对不上，多半是旧卷残留，`make down-v && make up` 即可。

### 常见问题

**某个服务一直 unhealthy。** `docker compose ps` 看是谁，再 `docker compose logs <服务名>`。backend 最常见的是等 Postgres/Kafka 健康检查、或 8080 已被本机 `spring-boot:run` 占用——不要同时 `make up` 和本机跑后端。

**端口已被占用。** 例如本机已经有 Postgres 占 5432：先停掉那个进程，或改 `docker-compose.yml` 的宿主机端口映射（改了的话 `.env` / 连接串也要一起改）。

**Apple Silicon / ARM。** Postgres 镜像由仓库自己的 `deploy/postgres/Dockerfile` 构建（官方 `postgres:18.6` + apt PostGIS + 源码 pgvector），有 linux/arm64，不依赖没有 arm64 manifest 的 `postgis/postgis`。

**改了 `.env` 不生效。** Compose 只在创建容器时读环境变量。改完后 `docker compose up -d --force-recreate backend`（或 `make down && make up`）。

日常改 Java/TS、跑单测，见下面「本地测试」。架构与状态机见 [`docs/architecture.md`](docs/architecture.md)。

## 这个项目重点验证什么

按计划的优先级排序：**库存/限购/权限正确性 > 支付/退款可恢复性 > 票券安全 > 事件顺序 > 推荐可评估性 > 完整体验**。

| 能力 | 实现位置 | 自动化验证 |
| --- | --- | --- |
| 无超卖 / 限购（协议 A：quota UPSERT → 锁 quota → 锁 inventory → 条件更新） | `service/BookingService` | `BookingConcurrencyIT`（100 并发 vs 50 容量；首次 quota 行并发） |
| 状态竞争唯一获胜（协议 B：booking → quota → inventory → reservation → tickets/payment_balance → user_wallet） | `service/BookingTransitions` | `BookingLifecycleIT`（支付单飞、超时 vs 支付、取消退款） |
| 请求指纹幂等（HMAC digest + canonical JSON hash，claim 随业务事务回滚） | `service/IdempotencyService` + `common/CanonicalJson` | `BookingConcurrencyIT` / `CanonicalJsonTest` |
| 单活动支付意图（部分唯一索引）+ 事务内钱包扣款/退款 + 退款额度预占 | `service/BookingTransitions` + `user_wallets` | `BookingLifecycleIT` |
| 退款额度预占（captured / refund_reserved / refunded 同行 CHECK） | `db/migration/V3` | `BookingLifecycleIT` |
| 票券 CSPRNG token + pepper 哈希 + 原子核销 + 重复扫码幂等 | `service/TicketService` | `TicketRedeemIT` |
| 无间隙 Outbox 序号（aggregate counter 行锁递增，回滚无洞）+ relay + consumer cursor + gap/DLT 恢复 | `outbox/*` | `OutboxKafkaIT` |
| 签名 keyset 搜索 cursor（filter hash + queryAsOf 服务端生成）+ PostGIS 半径搜索 | `service/CatalogueService` | 冒烟脚本 + 手工 |
| 推荐 V0/V1（冻结候选 cursor、reason codes、pgvector 可选）+ 互动事件 | `service/RecommendationService` | 冒烟脚本 |
| 最小 SSE（Origin 校验 + 所有权 + 心跳，REST 兜底） | `controller/BookingSseController` | 手工 curl 验证 |
| 推荐离线评估（时间切分 / NDCG@10 / Recall@10 / coverage / diversity / bootstrap CI，synthetic 标注） | `ml/` | `uv run pytest`（3 项，可复现） |

## 本地测试

CI（`.github/workflows/ci.yml`）跑的就是下面这几层。本机按同一顺序复现即可。

### 前置依赖

| 用途 | 工具 | 说明 |
| --- | --- | --- |
| 必选 | Docker Desktop / Engine + Compose | 后端集成测试（Testcontainers）和 Compose 栈都要 Docker |
| 后端测试 / 本机跑 API | JDK 21 + Maven 3.9+ | 仓库根 `pom.xml` 是 reactor，`mvn` 在根目录执行 |
| 前端测试 / Vite | Node 24 | `frontend/package-lock.json` 锁定 |
| ML 评估 | [uv](https://docs.astral.sh/uv/)（Python 3.13） | `ml/uv.lock` 锁定；`--frozen` 禁止改 lock |
| 可选 | curl、python3 | `make smoke` 用；k6 仅负载脚本需要 |

### 1. 一键栈 + API 冒烟（不装 JDK/Node）

对正在运行的 Compose 栈做全链路 HTTP 断言：注册 → 搜索 → 创建预订（幂等重放/冲突）→ 支付 → 出票 → 核销 → 双扫拒绝 → 支付前取消。

```bash
make up                    # 第一次会构建 postgres/backend/frontend 镜像，约数分钟
make ps                    # postgres/kafka/redis/backend/frontend 均应 healthy
make smoke                 # 默认打 http://localhost:8080
# BASE_URL=http://localhost:3000/api  bash scripts/smoke-test.sh   # 经 nginx 反代
make logs                  # 跟 backend 日志；Ctrl-C 退出
```

`make down` 停栈；`make down-v` 连 Postgres 数据卷一起删（下次 `up` 会重新 seed）。

### 2. 只起基础设施，本机跑后端 + Vite（日常开发）

Kafka 对外广告 `127.0.0.1:9092`（避免 macOS 上 `localhost` 走 IPv6；容器内 compose 服务仍走 `kafka:9092`），Postgres `5432`、Redis `6379` 也映射到宿主，默认 `application.yml` 无需改。

```bash
make infra                 # 只启动 postgres / kafka / redis
# 终端 1 — demo profile 会跑 Flyway 并播种演示账号
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=demo
# 终端 2 — Vite 把 /api 代理到 :8080
cd frontend && npm ci && npm run dev
open http://localhost:5173
```

改完 Java/TS 后重启对应进程即可，不必重建镜像。测完 `make down`。

不要同时 `make up` 和本机 `spring-boot:run`：两边都会占 8080。

### 3. 后端单元 + 集成测试（Testcontainers）

集成测试启动真实 PostgreSQL 18 + PostGIS + pgvector（镜像 tag `eventpulse/postgres:18-3.6-pgvector`，与 Compose 相同 Dockerfile）。Ryuk 已关闭，测完由 Makefile 清理残留容器。需要 Docker，**不**需要先 `make up`。

```bash
make test                  # 构建 postgres 测试镜像 → mvn verify → 清理 Testcontainers
# 等价拆开：
docker build -t eventpulse/postgres:18-3.6-pgvector deploy/postgres
mvn verify                 # 仓库根；含并发无超卖、限购、幂等、支付单飞、
                           # 钱包余额不足、退款预占、票券双扫、Outbox 无间隙/回滚无洞、消费者 gap、DLT
```

只跑某一个 IT 类：`mvn -pl backend -Dtest=BookingConcurrencyIT test`。

### 4. 前端 lint / 单测 / Playwright

Playwright 打的是 Vite dev server，**不需要后端**（API 用 route mock）。首次 e2e 会下载 Chromium。

```bash
make test-frontend         # lint + Vitest coverage（80% 门禁）+ Playwright
# 或拆开：
cd frontend
npm ci
npm run lint
npm run test               # Vitest，jsdom
npm run coverage           # 与 CI 相同的覆盖率门禁
npx playwright install --with-deps chromium
npm run e2e                # SPA 启动 / 登录表单 / 核心路由
```

### 5. ML 离线评估

合成数据上的时间切分 / NDCG@10 / Recall@10 / coverage / diversity / bootstrap CI，与线上效果无关。

```bash
make test-ml               # uv sync --frozen && pytest
# 或：
cd ml && uv sync --frozen && uv run pytest -q
cd ml && uv run python -m ml_eval.evaluate    # 打印完整评估报告
```

### 6. 全量（对齐 CI 的测试部分）

```bash
make test-all              # backend + frontend + ml，与 CI 三 job 对应
```

不含 gitleaks / dependency-review（那些只在 GitHub Actions 跑）。

### 7. 可选：k6 负载

需要一个已发布活动的 `EVENT_ID` / `TIER_ID`（可用冒烟脚本刚创建的，或 demo seed 的活动）。

```bash
make up                    # 或 make infra + 本机 backend
k6 run -e BASE_URL=http://localhost:8080 -e EVENT_ID=<uuid> -e TIER_ID=<uuid> scripts/k6/booking.js
# 无本地 k6：
docker run --rm -i --network host grafana/k6 run \
  -e BASE_URL=http://localhost:8080 -e EVENT_ID=<uuid> -e TIER_ID=<uuid> \
  - < scripts/k6/booking.js
```

库存正确性以 `BookingConcurrencyIT` 为准；k6 只看成功率与 p95，超卖表现为成功数 > 容量。

### Make 目标速查

| 目标 | 做什么 |
| --- | --- |
| `make up` | 构建并启动完整 demo 栈 |
| `make infra` | 只启动 postgres/kafka/redis |
| `make test` | 后端 `mvn verify`（含 Testcontainers） |
| `make test-frontend` | ESLint + Vitest coverage + Playwright |
| `make test-ml` | `uv run pytest` |
| `make test-all` | 上面三层一起 |
| `make smoke` | 对已启动栈做 API 冒烟 |
| `make down` / `make down-v` | 停栈 / 停栈并删数据卷 |
| `make psql` | 进入 Compose 里的 psql |

## 技术基线（详见 docs/adr/ADR-005）

| 层 | 版本 |
| --- | --- |
| Java / Spring Boot | 21 / 4.1.1（第一周兼容性 spike 通过后冻结） |
| 前端 | React 19.2.7 + TypeScript 5.9 + Vite 8.1 |
| 数据 | PostgreSQL 18.6 / PostGIS 3.6.2 / pgvector 0.8.6（源码编译进镜像） |
| 消息 / 缓存 | Kafka 4.3.1（KRaft 单节点，demo 用）/ Redis 8.2.9 |
| 测试 | JUnit 5、Testcontainers（PostGIS + Kafka）；Vitest + Playwright；ml 评估用 uv + pytest |
| 构建 | Maven 3.9+（`mvn`）、npm/uv lockfile、CycloneDX SBOM |
| CI | GitHub Actions（`.github/workflows/ci.yml`）：backend `mvn verify` / frontend lint+coverage+e2e+build / ml pytest |

## 仓库结构

```
backend/    Spring Boot 模块化单体，代码按技术分层（controller/ service/ service.impl/
            dto/ exception/ config/ common/ + batch/outbox/payment/seed/security 基础设施包），
            模块业务职责与事务边界不变，Flyway 迁移即不变量
frontend/   React 19 SPA（发现/详情/结算/订单/票券二维码/主办方/核销/管理异常视图）
deploy/     postgres(Dockerfile) / backend(Dockerfile) / frontend(Dockerfile+nginx.conf)
scripts/    smoke-test.sh（全链路冒烟）、k6/booking.js（负载脚本）
docs/       architecture.md（架构/ER/状态机/时序图）、adr/、api-catalog.md、
            event-catalog.md、security-matrix.md、runbooks.md、demo-script.md
```

## 可观测性

`/actuator/prometheus` 除 JVM/连接池指标外还暴露 outbox oldest pending、consumer
lag、command lease age、MANUAL_REVIEW、票券核销拒绝率和库存等式校验等业务指标。
Grafana dashboard 与 Prometheus 告警规则见 `deploy/observability/`，可直接导入演示。

## 生产化边界（诚实声明）

本仓库交付的是 demo 级闭环：单节点 Kafka/Redis 无故障容错；支付为进程内用户钱包（开户赠金，无充值 API）；
Kubernetes/Helm 等不属于 MVP。进入真实生产前需补齐备份/恢复演练、RPO/RTO、密钥轮换、
真实支付/身份供应商合规等（见计划 §12 与 docs/security-matrix.md）。
