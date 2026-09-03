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
make down                    # 停掉全部容器并删数据卷（从干净状态开始）
make testcontainers-cleanup  # 清掉 Testcontainers 残留容器
make up                      # 构建并启动，默认 2 个 api + 2 个 worker
make ps                      # postgres / redis / kafka / seeder / api ×2 / worker ×2 / frontend
```

`make up` 默认就是多实例（`API=2 WORKER=2`）：这个项目要验证的正是分布式行为，
单实例跑不出 SSE 跨实例送达、Outbox 多 Worker 领取、Kafka 分区再均衡这些路径。
副本数同时写在 `docker-compose.yml` 的 `deploy.replicas`，所以直接
`docker compose up -d` 也是 2 + 2。临时改规模：

```bash
make up API=3 WORKER=1       # 3 个 api + 1 个 worker
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
make up-runtime       # 启动 api / worker / frontend（同样默认各 2 个实例）
make up-distributed   # 等价于 make up，保留的旧名字
make test-distributed # 起多实例 + 端到端冒烟
```

多 Worker 依赖 Outbox 领取机制（`claimed_until` 租约 + 心跳续租 + 数据库条件
更新），不重不丢；api 多实例靠 Redis 广播做 SSE 跨实例送达。

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
六座城市：柏林、纽约、伦敦、东京、墨尔本、圣保罗，以及全部六种状态）、18 笔订单与对应电子票、收藏、行为流水、每日统计
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
| 普通用户 | `priya@eventpulse.dev` | `User123456` | Priya Sharma |
| 普通用户 | `diego@eventpulse.dev` | `User123456` | Diego Ramirez |
| 普通用户 | `amara@eventpulse.dev` | `User123456` | Amara Okafor |
| 普通用户 | `yuki@eventpulse.dev` | `User123456` | Yuki Tanaka |

主办方账号之间的活动互相隔离，可以用来验证越权访问被正确拦截。

```bash
make logs        # 跟随 api / worker 日志
make stop        # 停掉全部容器，保留数据卷（演示数据、账号、订单都还在）
make down        # 停掉全部容器并删数据卷；下次 make up 会重跑迁移并重新 seed
```

`make down` 会清数据：它用 `docker compose down -v --remove-orphans`，
连 `--scale` 起的额外实例和改过服务定义后残留的孤儿容器一起停掉，
并删除 `pgdata` 卷。想保留数据只停容器，用 `make stop`。
（`make down-v` 保留为 `make down` 的旧名字。）

## Kubernetes 部署

`deploy/k8s/` 下是同一镜像的三种角色清单：

```text
configmap.yml         普通配置（数据库 / Kafka / Redis 地址、partition、心跳、批量、AI 网关地址）
ai-configmap.yml      AI 服务非敏感配置（LLM provider / model / 超时）
secret.example.yml    敏感配置示例（DB_PASSWORD / SECRET_KEY / AI 服务间凭证），复制为 secret.yml 使用
api-deployment.yml    api，2 副本，readiness/liveness 分离，优雅停机 40s
api-service.yml       ClusterIP Service
worker-deployment.yml worker，第一版 1 副本（可安全扩到 2），只暴露 Actuator
seeder-job.yml        Job（名称带版本，backoffLimit=3，restartPolicy=Never）
ai-service-deployment.yml  Python AI 服务（FastAPI + LangChain），1 副本起，可扩
ai-service-service.yml     ClusterIP Service（仅集群内，不在 Ingress 暴露）
ingress.yml           /api 转发到 api Service；关闭缓冲、放长超时以支持 SSE
```

```bash
kubectl apply -f deploy/k8s/configmap.yml -f deploy/k8s/ai-configmap.yml
kubectl apply -f deploy/k8s/secret.example.yml   # 先填好真实值或换成 sealed-secrets
kubectl apply -f deploy/k8s/seeder-job.yml
kubectl wait --for=condition=complete job/eventpulse-seeder-v1 --timeout=300s
kubectl apply -f deploy/k8s/api-deployment.yml -f deploy/k8s/api-service.yml \
              -f deploy/k8s/worker-deployment.yml \
              -f deploy/k8s/ai-service-deployment.yml -f deploy/k8s/ai-service-service.yml \
              -f deploy/k8s/ingress.yml
```

发布流程：先等 Seeder Job 成功（`kubectl wait ... condition=complete`），
再确认 API / Worker 滚动更新完成；Job 失败时停止发布并保留日志。
镜像名目前是占位的 `eventpulse/backend:v1.0`，发布前替换成 registry 实际镜像
（例如 `ghcr.io/<owner>/eventpulse-backend:<commit-sha>`）。
所有 API 实例共享同一 `SECRET_KEY`，api / worker / seeder 共用同一套数据库连接。

### Jenkins 自动更新 k3s-home

AI 流水线使用节点本地的 `emptyDir` 工作卷，把 uv 缓存和 `.venv` 放在同一
文件系统，通过硬链接安装依赖，避免从 NFS 逐个复制大量小文件。缓存随构建 Pod
删除，每次新构建会重新下载依赖；不再使用共享 Maven PVC 保存 uv 缓存。
依赖同步仍使用 `uv sync --frozen --extra dev`，测试通过 `uv run --no-sync pytest`
复用刚安装的环境。同步阶段输出 uv 缓存路径与耗时，便于比较实际 CI 性能。

三个 Jenkinsfile 沿用 nightdeal 的发布方式：main 分支推送 GHCR 镜像成功后，
在独立的 `gitops` 容器中更新 `kaiwenyao/k3s-home` 的 main 分支。PR 和普通分支
不会写入配置仓库；流水线失败或变为 unstable 时也不会继续发布。

| 流水线 | 自动更新的清单（位于 `apps/eventpulse/`） |
| --- | --- |
| backend | `api-deployment.yaml`、`worker-deployment.yaml` |
| frontend | `frontend-deployment.yaml` |
| ai-service | `ai-service-deployment.yaml` |

Jenkins 需能访问与 nightdeal 相同的 `k3s-home-write` 凭据（Username with password，
密码为拥有 k3s-home Contents 写权限的 GitHub token）。镜像推送继续使用 `ghcr-token`。
`scripts/update-k3s-home.sh` 直接使用刚推送的 `FULL_IMAGE`，只替换对应镜像行；
版本未变化时不创建提交，目标清单缺失或镜像不匹配时让构建失败。三个任务同时推送
发生冲突时，会从远端最新 main 重新应用本服务的修改，最多尝试五次，不强推。

`seeder-job.yaml` 单独维护：已创建 Job 的 Pod 模板不可变，需要更新时应调整
镜像并按 k3s-home 的说明重建 Job。此流程只提交部署配置，集群应用仍按现有运维
流程进行。GitOps 脚本的集成测试使用临时本地仓库，不访问 GitHub：

```bash
python3 -m unittest discover -s scripts/tests -v
```

## AI 助手

AI 是运行时调用的外部 LLM 能力（不训练模型、不做向量化）。架构：

```text
浏览器 ──> Spring Boot /api/ai/** ──> Python AI Service ──> 外部 LLM
                      │  （Agent 需要业务数据时）
                      └────< /internal/ai-tools/**（服务间凭证 + 短期签名用户上下文）──> PostgreSQL
```

两个功能：

| 功能 | 入口 | 说明 |
| --- | --- | --- |
| 主办方文案完善 | `POST /api/ai/organiser/improve-event`（JWT ORGANISER） | 普通 LLM 调用 + 结构化输出；建议先在前端确认，再走普通保存/发布接口；不自动保存 |
| 自然语言找活动 | `POST /api/ai/discovery/chat`（可选 JWT） | LangChain Agent 通过只读工具查真实活动；登录用户的会话存 PostgreSQL，游客单轮；Spring Boot 返回前再次复核活动可见性 |

边界与降级：

- 浏览器永远不直接访问 Python 服务；LLM API Key 只存在于 ai-service 的 Secret。
- `/internal/**` 需要服务间凭证，不经公网 Ingress 暴露；userId 来自 Spring Boot
  签发的短期 token，模型与请求体都决定不了身份。
- 未配置 `LLM_API_KEY` 时 AI 接口明确返回不可用，普通搜索、编辑、预订不受影响。
- 限流（用户/IP 每分钟）、工具调用次数、输入输出长度、超时与重试都有上限；
  LLM 输出按不可信数据处理，编造或已下架的活动 ID 会被丢弃。
- 全链路记录 `ai_requests`（状态、耗时、token 用量），不含密钥与完整提示词。

配置在 `.env.example` 的 `AI` 段（provider / model / key / base_url / 超时 /
服务间凭证）。模型是 OpenAI 兼容的任意网关均可（配 `LLM_BASE_URL`）；
**reasoning 类模型（如 deepseek-v4）注意**：思考 token 计入
`LLM_MAX_OUTPUT_TOKENS` 输出预算，预算太小（如 1024）会导致空回复，
默认已设 4096。本地调试：`make up` 后打开 http://localhost:3000，
首页「AI 找活动」即可提问；主办方登录后进活动表单页点「AI 完善文案」。

## 本地开发

本机需要 JDK 21、Maven、Node.js。同样先清残留，再只起基础设施：

```bash
make down
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
# 终端 4：Python AI 服务（LLM_API_KEY 留空时 AI 明确显示不可用）
cd ai-service && uv sync && uv run uvicorn app.main:app --port 8090
```

```bash
# 终端 5
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
| `AiMigrationIT` | V9 迁移：全新建库 + 旧库升级两条路径，旧推荐表被删除 |
| `AiGatewayServiceTest` / `AiServiceClientTest` / `InternalServiceInterceptorTest` | AI 网关限流、活动复核、编造 ID 过滤、服务间认证 |

Python AI 服务的测试（模拟 LLM 与工具响应，CI 不调用付费模型、不需要真实 Key）：

```bash
make test-ai
```

```bash
make testcontainers-cleanup  # 清掉 Testcontainers 残留容器
make test                    # 后端 mvn verify（单测 + IT + JaCoCo 90% 线覆盖门槛）
make test-frontend           # ESLint + Vitest + Playwright
make test-all                # 后端 + 前端 + AI 服务三层
```

整栈冒烟（需要本机 `curl` 和 `python3`）：

```bash
make up                       # 默认 2 个 api + 2 个 worker
make smoke                    # 默认打 http://localhost:3000（前端反代）
# api 不再固定绑定宿主机端口（多实例会冲突），统一从前端 Nginx 进；
# 要直连某个实例：docker compose exec api curl -s localhost:8080/actuator/health
```

全部 PASS 会打印 `SMOKE TEST: ALL GREEN`。
