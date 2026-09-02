# EventPulse

## 运行

本机需要 Docker Desktop（macOS / Windows）或 Docker Engine + Compose v2（Linux）。

同一个后端镜像通过 Spring Profile 运行三种角色（与 Kubernetes 部署一致）：

```text
                     ┌──────────┐
      seeder  Job    │  seeder  │ 一次性初始化演示数据，完成后退出
                     └──────────┘
      api Deployment │   api    │ 只处理 HTTP 与 SSE，可多副本
                     └──────────┘
   worker Deployment │  worker  │ Kafka 消费 / Outbox 发送 / 活动生命周期
                     └──────────┘
```

先清掉可能残留的 Compose 环境和 Testcontainers 容器，再启动：

```bash
cp .env.example .env
make down-v                  # 停栈并删数据卷
make testcontainers-cleanup  # 清掉 Testcontainers 残留容器
make up                      # docker compose up -d --build
make ps                      # postgres / redis / kafka / seeder / api / worker / frontend
```

启动顺序：PostgreSQL / Redis / Kafka 健康 → `seeder` 播种并成功退出 →
`api` 与 `worker` 启动（compose 的 `service_completed_successfully` 依赖）。
第一次会构建镜像，大约几分钟；api 健康检查有 60s `start_period`。

| 端口 | 服务 |
| --- | --- |
| 3000 | 前端（`/api` 反代到 api 服务，SSE 已做免缓冲） |
| 5432 | PostgreSQL |
| 6379 | Redis |
| 9092 | Kafka（宿主机侧 19092） |

```bash
# 走前端反代验证（api 不再固定占用宿主机 8080，才能 --scale 扩容）
curl -s http://localhost:3000/actuator/health
# 期望：{"status":"UP", ...}
```

打开 http://localhost:3000

`SECRET_KEY` 用于 JWT 签名，`DB_PASSWORD` 是 Postgres 密码；demo 可直接用 `.env.example` 默认值。

### 分布式验证命令

```bash
make up-infra         # 只启动 PostgreSQL / Redis / Kafka
make seed             # 只运行 seeder，退出码透传
make up-runtime       # 启动 api / worker / frontend
make up-distributed   # 2 个 api + 2 个 worker（Outbox 领取租约 + 心跳续租，不重不丢）
make test-distributed # up-distributed + 端到端冒烟
```

### 多实例行为一览

- **热门与统计不落本机 JVM**：热门活动只缓存到 Redis（30s 过期 + 变更即删），
  Redis 不可用时直接回源 PostgreSQL；缓存降级次数走 Micrometer 指标
  （`eventpulse.cache.fallbacks`），不再保存在某个实例的字段里。
- **SSE 跨实例送达**：浏览器连到任意 api 实例；worker 在数据库事务提交成功后
  向 Redis 发一条轻量「有变化」提醒（`eventpulse:sse` 频道），所有 api 实例
  订阅后推给连接在自己身上的浏览器。订单/票据的最终状态以 PostgreSQL 为准，
  前端收到提醒后重新拉 REST，断线期间的变化在重连后自动补回。
  SSE 订阅走 `Authorization: Bearer` 头并校验订单所有权；同一订单允许多个
  标签页连接，心跳 25s，api 停机时主动关闭连接让浏览器立刻重连其他实例。
- **多 Worker 安全**：Outbox 用一条带 `FOR UPDATE SKIP LOCKED` 的原子 UPDATE
  领取（`claimed_by` / `claimed_until` 租约），每条发送前给整批续租——Worker
  活着租约就不会在批中途过期，一条消息不会被两个 Worker 同时处理；Worker
  崩溃后停止续租，租约到期其他 Worker 接手，重发由消费端 `consumed_events`
  幂等表兜底。一轮结束（含提前退出）统一归还剩余租约，一次 Kafka 抖动不会
  让中继停摆一个租约周期。同一订单的消息用 `message_key` 进同一 Kafka
  partition 保序。活动生命周期是两条数据库条件更新，多 Worker 并发执行只会
  更新 0 行，没有乐观锁冲突。活动生命周期是两条数据库条件更新，
  多 Worker 并发执行只会更新 0 行，没有乐观锁冲突。
- **Seeder 幂等**：`seed_runs` 表记录完成的版本（与播种同一事务），
  Kubernetes Job 重试或人工重跑不会产生重复数据。

`demo` profile 已由 `seeder` 角色取代：8 个账号、19 个活动（覆盖四个分类、
六座城市和全部六种状态）、18 笔订单与对应电子票、收藏、行为流水、每日统计
和站内消息。种子内容集中在
`backend/src/main/java/dev/kaiwen/eventpulse/seed/DemoCatalog.java`，
改 demo 数据只改这一个文件。

活动时间都是相对启动时刻算的，所以「未开始 / 进行中 / 已结束」永远是自洽的；
统计曲线由活动 ID 推导，不用随机数，每次播种得到的数字一致。

| 角色 | 邮箱 | 密码 | 说明 |
| --- | --- | --- | --- |
| 普通用户 | `user@eventpulse.dev` | `User123456` | 有订单、电子票、收藏和消息 |
| 主办方 | `organiser@eventpulse.dev` | `Organiser123456` | 拥有大部分活动，含草稿与已归档 |
| 主办方 | `studio@eventpulse.dev` | `Organiser123456` | 声浪现场，含一个已取消的音乐节 |
| 主办方 | `guild@eventpulse.dev` | `Organiser123456` | 城市漫游者 |
| 普通用户 | `lin@eventpulse.dev` | `User123456` | 林可可 |
| 普通用户 | `zhao@eventpulse.dev` | `User123456` | 赵一鸣 |
| 普通用户 | `chen@eventpulse.dev` | `User123456` | 陈思远 |
| 普通用户 | `wang@eventpulse.dev` | `User123456` | 王雨桐 |

主办方账号之间的活动互相隔离，可以用来验证越权访问被正确拦截。

```bash
make logs        # 跟随 api / worker 日志
make down        # 停容器，保留数据卷
make down-v      # 停容器并删数据卷；下次 make up 会重新 seed
```

## Kubernetes 部署

`deploy/k8s/` 下是同一镜像的三种角色清单：

```text
configmap.yml         普通配置（数据库 / Kafka / Redis 地址、partition、心跳、批量）
secret.example.yml    敏感配置示例（DB_PASSWORD / SECRET_KEY），复制为 secret.yml 使用
api-deployment.yml    api，2 副本，readiness/liveness 分离，优雅停机 40s
api-service.yml       ClusterIP Service
worker-deployment.yml worker，第一版 1 副本（可安全扩到 2），只暴露 Actuator
seeder-job.yml        Job（名称带版本，backoffLimit=3，restartPolicy=Never）
ingress.yml           /api 转发到 api Service；关闭缓冲、放长超时以支持 SSE
```

```bash
kubectl apply -f deploy/k8s/configmap.yml
kubectl apply -f deploy/k8s/secret.example.yml   # 先填好真实值或换成 sealed-secrets
kubectl apply -f deploy/k8s/seeder-job.yml
kubectl wait --for=condition=complete job/eventpulse-seeder-v1 --timeout=300s
kubectl apply -f deploy/k8s/api-deployment.yml -f deploy/k8s/api-service.yml \
              -f deploy/k8s/worker-deployment.yml -f deploy/k8s/ingress.yml
```

发布流程：先等 Seeder Job 成功（`kubectl wait ... condition=complete`），
再确认 API / Worker 滚动更新完成；Job 失败时停止发布并保留日志。
镜像名目前是占位的 `eventpulse/backend:v1.0`，发布前替换成 registry 实际镜像
（例如 `ghcr.io/<owner>/eventpulse-backend:<commit-sha>`）。
所有 API 实例共享同一 `SECRET_KEY`，api / worker / seeder 共用同一套数据库连接。

## 本地开发

本机需要 JDK 21、Maven、Node.js。同样先清残留，再只起基础设施：

```bash
make down-v
make testcontainers-cleanup
make up-infra    # 只启动 postgres / redis / kafka
```

```bash
# 终端 1：api 角色（只处理 HTTP 与 SSE）
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=api
```

```bash
# 终端 2：worker 角色（Kafka / Outbox / 生命周期）
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=worker
```

```bash
# 终端 3：播种一次（seeder 角色，跑完即退出）
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=seeder
```

```bash
# 终端 4
cd frontend && npm ci && npm run dev
```

前端：http://localhost:5173

### 前端结构

```
frontend/src
├─ App.tsx           路由与应用外壳（顶栏 / Toast / 页脚）
├─ api.ts  auth.tsx  网络层与会话
├─ types.ts          与后端 DTO 对应的视图模型、分类与状态字典
├─ lib/              纯函数工具（ISO ↔ datetime-local 转换、相对时间、SSE 订阅）
├─ ui/               设计系统原语：Field / Badges / Modal / Toast / Skeleton / Icons
├─ components/       跨页面组件（顶栏、活动票卡）
├─ pages/            观众端页面（发现、详情、登录、预订、收藏、消息）
├─ organiser/        主办方控制台（概览、活动表格、发布表单、生命周期、参与者、数据）
└─ styles/           按关注点拆分的样式表，由 styles.css 汇总导入
```

发布活动的表单逻辑（默认值、字段校验、请求体映射）集中在
`organiser/eventForm.ts`，是纯函数，单独做了单元测试；页面组件只负责把状态接到
表单控件上。订单详情页通过 `lib/sse.ts` 订阅订单事件提醒（fetch 实现、
Authorization 头、指数退避自动重连），收到提醒后重新拉取 REST 数据。

## 测试

后端测试会拉 Testcontainers（本机需要 Docker），`*IT.java` 覆盖分布式行为：

| 测试 | 覆盖 |
| --- | --- |
| `OutboxClaimIT` | 双 Worker 并发领取不重复、同键保序、租约到期接手、隔离不阻塞 |
| `KafkaOutboxE2EIT` | 真实 Kafka：Outbox → Relay → Consumer → 通知落库，同键保序，topic 分区数 |
| `KafkaPartitionIT` | topic 按配置建 3 分区、同组双 Worker 分摊分区且都消费、同键同分区有序 |
| `SseReminderDeliveryIT` | 真实 Redis：发布 → 广播 → 订阅 → 本机连接，重放去重 |
| `*ProfileWiringIT` | api / worker / seeder 三个 Profile 各自装配了什么、排除了什么 |
| `JwtInterceptorAsyncTest` | SSE 异步请求的 ThreadLocal 清理、线程复用不串身份 |
| `BookingConcurrencyIT` | 并发下单不超卖 |

```bash
make testcontainers-cleanup  # 清掉 Testcontainers 残留容器
make test                    # 后端 mvn verify（单测 + IT + JaCoCo 90% 线覆盖门槛）
make test-frontend           # ESLint + Vitest + Playwright
make test-all                # 上面两层
```

整栈冒烟（需要本机 `curl` 和 `python3`）：

```bash
make up                       # 或 make up-distributed 起双实例
make smoke                    # 默认打 http://localhost:3000（前端反代）
# BASE_URL=http://localhost:8080 bash scripts/smoke-test.sh 可指向单实例
```

全部 PASS 会打印 `SMOKE TEST: ALL GREEN`。
