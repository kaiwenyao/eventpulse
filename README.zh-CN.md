# 🚀 EventPulse

[English](README.md) | **简体中文**

---

**EventPulse** 是一个分布式活动票务平台，覆盖观众购票与主办方办展的全流程：
活动发现 / 搜索 / 收藏、下单购票、电子票、购物车、钱包流水、订单实时通知，
以及主办方的活动发布、生命周期管理与参与者数据。同一个后端镜像以
**api / worker / seeder** 三种角色运行，`make up` 默认拉起 2 个 api + 2 个 worker
——这个项目要验证的正是分布式行为：SSE 跨实例送达、Outbox 多 Worker 领取、
Kafka 分区再均衡。本地 Docker Compose 一键起全栈，同一套镜像可直接部署到
Kubernetes（k3s）。🎉

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21 · Spring Boot · PostgreSQL · Redis · Kafka |
| 前端 | React 19 · TypeScript · Vite |
| AI 服务 | Python 3.12 · FastAPI · LangChain |
| 基础设施 | Docker Compose · Kubernetes（k3s）· Jenkins · GitHub Actions |

---

## 📋 目录

- [✨ 特性](#-特性)
- [🚀 快速开始](#-快速开始)
  - [🔧 环境要求](#-环境要求)
  - [🐳 Docker Compose 启动](#-docker-compose-启动)
  - [🔑 配置](#-配置)
- [💻 使用](#-使用)
- [🧩 架构与多实例设计](#-架构与多实例设计)
- [🤖 AI 助手](#-ai-助手)
- [📷 图片存储（SeaweedFS S3）](#-图片存储seaweedfs-s3)
- [📦 Kubernetes 部署](#-kubernetes-部署)
- [🔨 本地开发](#-本地开发)
- [🧪 测试](#-测试)
- [🤝 贡献](#-贡献)
- [📝 许可证](#-许可证)
- [📧 联系](#-联系)

---

## ✨ 特性

- **🎫 全流程票务**：观众发现 / 搜索 / 收藏活动，下单购票，订单与电子票实时跟踪；主办方发布活动、管理生命周期与参与者数据。
- **🛒 购物车与钱包**：跨设备购物车、结算一次事务结清；购物车结算必须带 `Idempotency-Key`（直接下单 / 充值可选带上，享受同样的幂等保护），重试不会重复扣款；钱包流水记录变动前后余额，全程可追溯。
- **📡 实时通知（SSE）**：订单 / 钱包 / 购物车变化经 Outbox → Kafka → Redis 广播，送达连在任意 api 实例上的浏览器，断线重连自动补回。
- **🔁 消息不重不丢**：Outbox 用带 `FOR UPDATE SKIP LOCKED` 的原子 UPDATE 领取（租约 + 心跳续租），消费端 `consumed_events` 幂等表兜底，`message_key` 让同一订单进同一 Kafka 分区保序。
- **⚖️ 多实例安全**：热门与统计不落本机 JVM（Redis 缓存 + 回源 PostgreSQL）；并发下单不超卖；活动生命周期靠数据库条件更新，多 Worker 并发执行只会更新 0 行。
- **🤖 AI 助手**：自然语言找活动（LangChain Agent 经只读工具查真实活动）+ 主办方文案完善（结构化输出）；未配置 Key 时明确返回不可用，普通业务不受影响。
- **📷 图片对象存储**：SeaweedFS S3 兼容接口，公开直连或 `/api/media/images/{id}` 代理取址可配，软删除 + 宽限期后台清理。
- **🧪 测试与 CI**：Testcontainers 集成测试覆盖分布式路径，后端 90% 行覆盖率门槛；前端 ESLint + Vitest + Playwright；AI 服务用模拟 LLM 测试；GitHub Actions 跑全部检查，Jenkins 发布到 k3s。

---

## 🚀 快速开始

### 🔧 环境要求

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

### 🐳 Docker Compose 启动

先清掉可能残留的 Compose 环境和 Testcontainers 容器，再启动：

```bash
cp .env.example .env        # 1. 生成环境变量文件（demo 可直接用默认值）
make down                   # 2. 停掉全部容器并删数据卷（从干净状态开始）
make testcontainers-cleanup # 3. 清掉 Testcontainers 残留容器
make up                     # 4. 构建并启动，默认 2 个 api + 2 个 worker
make ps                     # 5. 查看容器状态：postgres / redis / kafka / seeder / api ×2 / worker ×2 / frontend
```

`make up` 默认就是多实例（`API=2 WORKER=2`）：单实例跑不出 SSE 跨实例送达、
Outbox 多 Worker 领取、Kafka 分区再均衡这些路径。副本数同时写在
`docker-compose.yml` 的 `deploy.replicas`，所以直接 `docker compose up -d`
也是 2 + 2。临时改规模：

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
| 9092 | Kafka（容器 EXTERNAL 监听 19092） |

```bash
# 走前端反代验证（api 不再固定占用宿主机 8080，才能 --scale 扩容）
curl -s http://localhost:3000/actuator/health
# 期望：{"status":"UP", ...}
```

打开 http://localhost:3000 即可使用。

### 🔑 配置

配置统一放在根目录 `.env`（从 `.env.example` 复制），demo 默认值可直接跑。主要变量：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `SECRET_KEY` | 占位值 | JWT 签名密钥；真实部署务必替换 dev 默认值（启动不做强制校验），轮换会使已发 token 失效 |
| `DB_PASSWORD` | `eventpulse` | PostgreSQL 密码 |
| `CORS_ORIGINS` | localhost 两端口 | 允许的跨域来源（前端反代 + Vite 开发端口） |
| `LLM_MODEL` / `LLM_API_KEY` | `gpt-4o-mini` / 空 | AI 服务的模型与凭证；Key 留空时 AI 明确返回不可用 |
| `LLM_BASE_URL` | 空 | OpenAI 兼容网关基址，须含 API 前缀（如 `https://host/v1`） |
| `LLM_MAX_OUTPUT_TOKENS` | `4096` | reasoning 类模型的思考 token 也计入，预算太小会空回复 |
| `AI_SERVICE_TOKEN` / `AI_INTERNAL_TOKEN` | dev 值 | Spring Boot ↔ ai-service 服务间凭证，真实部署必须更换 |
| `AI_RETENTION_DAYS` / `AI_REQUEST_LOG_RETENTION_DAYS` | `90` / `180` | AI 会话与调用日志的保留天数，由 worker 分批删除 |
| `S3_ENABLED` | `false` | true 时图片走 S3；多副本部署（`API>1` 或 k3s）必须开 |
| `S3_ENDPOINT` / `S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` | 空 | SeaweedFS S3 地址与凭证 |

完整清单（含注释）见 `.env.example`。

#### 分布式验证命令

```bash
make up-infra         # 只启动 PostgreSQL / Redis / Kafka
make seed             # 只运行 seeder，退出码透传
make up-runtime       # 启动 api / worker / frontend（同样默认各 2 个实例）
make up-distributed   # 等价于 make up，保留的旧名字
make test-distributed # 起多实例 + 端到端冒烟
```

多 Worker 依赖 Outbox 领取机制（`claimed_until` 租约 + 心跳续租 + 数据库条件
更新），不重不丢；api 多实例靠 Redis 广播做 SSE 跨实例送达。详见
[架构与多实例设计](#-架构与多实例设计)。

---

## 💻 使用

`seeder` 会播种 8 个账号、19 个活动（覆盖四个分类、六座城市：柏林、纽约、
伦敦、东京、墨尔本、圣保罗，以及全部六种状态）、18 笔订单与对应电子票、
收藏、行为流水、每日统计和站内消息。19 个活动各带封面：图片按
`DemoCatalog.EVENTS` 顺序预传到对象存储（key 固定为
`seed/demo-covers/NN.jpeg`，NN 为活动序号），播种时只写 `media_assets` 行并
把它接进活动的 `coverAssetId` / `coverUrl`，不做对象存储 IO；bucket 里对象缺失
时封面会 404，重传同名 key 即可修复。种子内容集中在
`backend/src/main/java/dev/kaiwen/eventpulse/seed/DemoCatalog.java`，
改 demo 数据只改这一个文件。

活动时间都是相对启动时刻算的，所以「未开始 / 进行中 / 已结束」永远是自洽的；
统计曲线由活动 ID 推导，不用随机数，每次播种得到的数字一致。

演示账号：

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
想亲手走一遍购物车 / 订单 / 钱包的验收路径，见
[docs/acceptance-walkthrough.md](docs/acceptance-walkthrough.md)（从零开始约 15 分钟）。

日常操作：

```bash
make logs        # 跟随 api / worker 日志
make stop        # 停掉全部容器，保留数据卷（演示数据、账号、订单都还在）
make down        # 停掉全部容器并删数据卷；下次 make up 会重跑迁移并重新 seed
```

`make down` 会清数据：它用 `docker compose down -v --remove-orphans`，
连 `--scale` 起的额外实例和改过服务定义后残留的孤儿容器一起停掉，
并删除 `pgdata` 卷。想保留数据只停容器，用 `make stop`。
（`make down-v` 保留为 `make down` 的旧名字。）

---

## 🧩 架构与多实例设计

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
  更新 0 行，没有乐观锁冲突。
- **Seeder 幂等**：`seed_runs` 表记录完成的版本（与播种同一事务），
  Kubernetes Job 重试或人工重跑不会产生重复数据。

### 购物车、历史订单与钱包流水

- **购物车**：登录后可在活动详情页「加入购物车」（不扣款、不占库存），数据库持久化、跨设备可见；支持数量调整、勾选、移除与清空，失效原因（取消 / 停售 / 售罄 / 价格变化…）逐项展示。结算时勾选项一次事务结清：每个活动一张独立订单，任一项不可购买或余额不足整次回滚。结算必须带 `Idempotency-Key` 头，重试 / 重复点击不会重复下单或扣款。
- **历史订单**：「我的预订」默认展示全部真实状态（含已取消），支持服务端分页、状态筛选、时间范围和订单号 / 活动名搜索；金额一律用订单快照展示，取消原因（已取消 / 已核销 / 活动已开始…）明确标注。`GET /api/bookings` 已从全量数组改为 `{total, records}` 分页结构（与前端同仓库同版本发布）。
- **钱包流水**：充值、下单扣款、用户取消退款、活动取消退款都在业务事务里写入 `wallet_ledger`（带正负号金额、变动前后余额、业务去重标识）；个人中心「余额明细」页可按类型 / 时间筛选并跳转关联订单。老账户迁移时自动生成一条期初余额记录，不改变余额。充值仍是演示功能，支持 `Idempotency-Key` 幂等。
- **事件**：在原有 `booking-events` 之外新增 `wallet-events`（流水已记账公告）与 `cart-events`（购物车变更公告），独立 consumer group、按用户分区、`consumed_events` 去重；Worker 在事务提交后经 Redis 向该用户的所有页面发 SSE 刷新提醒（`/api/user/events`）。Kafka 不可用时业务照常成功，消息留在 Outbox 恢复后投递。详见 [docs/order-flow.md](docs/order-flow.md)。

---

## 🤖 AI 助手

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
| 会话管理 | `GET/DELETE /api/ai/conversations[/{id}]`（JWT） | 列出、恢复、删除自己的发现助手会话；恢复只回放文字，因为服务端只存 `role`/`content` |

边界与降级：

- 浏览器永远不直接访问 Python 服务；LLM API Key 只存在于 ai-service 的 Secret。
- `/internal/**` 需要服务间凭证，不经公网 Ingress 暴露；userId 来自 Spring Boot
  签发的短期 token，模型与请求体都决定不了身份。
- 未配置 `LLM_API_KEY` 时 AI 接口明确返回不可用，普通搜索、编辑、预订不受影响。
- 限流（用户/IP 每分钟）、工具调用次数、输入输出长度、超时与重试都有上限；
  LLM 输出按不可信数据处理，编造或已下架的活动 ID 会被丢弃。
- 全链路记录 `ai_requests`（状态、耗时、token 用量），不含密钥与完整提示词。
- **保留期**：worker 按 `AI_RETENTION_DAYS`（90）清理会话、按
  `AI_REQUEST_LOG_RETENTION_DAYS`（180）清理调用日志，分批删除；用户也可以
  自己删除某段对话。

配置在 `.env.example` 的 `AI` 段（provider / model / key / base_url / 超时 /
服务间凭证 / 保留期）。模型是 OpenAI 兼容的任意网关均可（配 `LLM_BASE_URL`）；
**reasoning 类模型（如 deepseek-v4）注意**：思考 token 计入
`LLM_MAX_OUTPUT_TOKENS` 输出预算，预算太小（如 1024）会导致空回复，
默认已设 4096。本地调试：`make up` 后打开 http://localhost:3000，
首页「AI 找活动」即可提问；主办方登录后进活动表单页点「AI 完善文案」。

---

## 📷 图片存储（SeaweedFS S3）

图片（活动封面等）存在自建 SeaweedFS 的 S3 兼容接口上，不落在 api Pod 的本地
磁盘：两个 api 副本读写同一份对象，前端不用改。业务语义全部保留——上传仍要
登录、限 2MB、仅 JPEG/PNG/WebP，上传响应结构不变。对象 key 由后端
生成（UUID 前缀），Content-Type 随对象保存；上传成功但数据库保存失败会补偿
删除刚上传的对象；S3 不可达 / 凭证错误映射为 503（读取与上传），对象缺失
404，不会把存储故障说成请求错误。

删除是软删除：`DELETE` 只改数据库审计字段（`status=DELETED` + `deleted_at`），
权限校验不变；S3 对象由 worker 的清理任务在宽限期后统一删除并标记 `PURGED`
（只清理数据库里记录的、过了宽限期的 DELETED 对象；删除失败保持 DELETED 等
下轮重试；S3 delete 幂等，对象不存在视为已删除）。

配置（`application.yml` 的 `eventpulse.s3.*`，全部可被环境变量覆盖）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `S3_ENABLED` | `false` | true 时图片走 S3；false 回落本地磁盘（仅本地单机） |
| `S3_ENDPOINT` | 空 | 例如 `https://s3.kaiwen.dev` |
| `S3_REGION` | `us-east-1` | S3 兼容服务常为 us-east-1 |
| `S3_BUCKET` | `eventpulse` | 必须已存在；应用不创建 bucket、不改公开权限 |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | 空 | 专属身份的凭证，走环境变量 / K8s Secret，不入 git |
| `S3_PATH_STYLE` | `true` | SeaweedFS 用 path-style（`https://endpoint/bucket/key`） |
| `S3_PUBLIC_BASE_URL` | 空 | 浏览器直连基址（见下）；留空则图片走 `/api/media/images/{id}` 代理 |
| `S3_CONNECT_TIMEOUT` / `S3_READ_TIMEOUT` / `S3_API_CALL_TIMEOUT` | 2000 / 10000 / 30000 | 毫秒 |

### 图片的取址方式

后端在 `public_url` 字段里下发图片地址，**前端把它当不透明字符串直接用**，不要
自己拼 endpoint 和 key——换 CDN、换 bucket、或某类资产改走预签名时才不用动前端。

配了 `S3_PUBLIC_BASE_URL` 就是**公开直连**：`public_url` 指向对象存储，图片字节
不经过 api 进程，浏览器与 CDN 可长期缓存（对象上带
`Cache-Control: public, max-age=31536000, immutable`，key 含 UUID 内容不变）。
地址由 key 拼出，是纯字符串操作，不发起任何存储请求。

留空则回落到 `/api/media/images/{id}` **代理**：后端校验数据库状态后从存储读
内容回传。本地磁盘模式（`S3_ENABLED=false`）永远走这条路。两条路都保留，
`MediaController` 的 GET 端点不会下线。

直连要求 bucket 已授予匿名读，**应用不检查也不修改这个权限**。SeaweedFS 侧加
bucket policy（只给对象级 `s3:GetObject`）：

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PublicReadObjects",
      "Effect": "Allow",
      "Principal": "*",
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::eventpulse/*"]
    }
  ]
}
```

`Resource` 结尾的 `/*` 是对象级，不能写成 bucket 本身；`Action` 不要加
`s3:ListBucket`——用户上传的 key 带 UUID 不可枚举，列举权一旦开出去这层保护就
没了（也会暴露软删除宽限期内尚未清理的对象）。改完用不带凭证的请求核实：

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://s3.kaiwen.dev/eventpulse/seed/demo-covers/01.jpeg   # 期望 200
curl -s -o /dev/null -w "%{http_code}\n" https://s3.kaiwen.dev/eventpulse/                            # 期望 403
```

清理任务（worker 执行）：`MEDIA_PURGE_ENABLED`（默认 true）、
`MEDIA_PURGE_AFTER_DAYS`（宽限期天数，默认 7）、`MEDIA_PURGE_BATCH_SIZE`（50）、
`MEDIA_PURGE_FIXED_DELAY_MS`（3600000）。

### 本地磁盘回落（默认）

默认 `S3_ENABLED=false`，图片仍落在 `MEDIA_DIR`（默认 `data/media`），行为和
以前一致。要用 SeaweedFS，在运行环境设置上面几个变量即可：

```bash
S3_ENABLED=true S3_ENDPOINT=https://s3.kaiwen.dev S3_BUCKET=eventpulse \
S3_ACCESS_KEY=... S3_SECRET_KEY=... \
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=api
```

compose 里同样把这几个变量放进 `.env`（已在 `docker-compose.yml` 透传）；
`.env.example` 有完整清单。多副本（`API>1` 或 k3s）必须启用 S3。

### k3s

`k3s-home/apps/eventpulse/configmap.yaml` 已带 S3 的非敏感变量（三个角色共用
同一套 envFrom：api 读写对象，worker 执行清理，seeder 仅需能启动），凭证
`S3_ACCESS_KEY` / `S3_SECRET_KEY` 封进 `sealed-secret.yaml`。SeaweedFS 侧的
身份、bucket 与权限核实见 k3s-home 仓库 README 的「S3 图片存储」。

### 已有本地图片怎么办（迁移方案，未执行）

历史图片在各 api 容器的 `data/media` 里，compose / k8s 都没有给它挂持久卷，
容器重建即丢，通常无需迁移。若确有要保留的本地文件，按下面顺序迁（不删除
本地文件，出问题可重来）：

1. 对象 key 直接沿用数据库里的 `storage_key`，无需改任何数据库记录：
   `aws s3 sync ./data/media/ s3://eventpulse/ --endpoint-url https://s3.kaiwen.dev`
   （key 以 `.png` / `.jpg` / `.webp` 结尾，CLI 能猜对 Content-Type；
   `S3_ACCESS_KEY` / `S3_SECRET_KEY` 走环境变量。）
2. sync 完成后，把 `S3_ENABLED=true` 随新镜像一起滚动更新。
3. 切换后新上传直接进 S3；若有实例在切换窗口内还往本地写过文件，再 sync 一次
   补齐。确认无误前不要删本地目录，确认后删除也只影响本机残留。

---

## 📦 Kubernetes 部署

`deploy/k8s/` 下是同一镜像的三种角色清单：

```text
configmap.yml         普通配置（数据库 / Kafka / Redis 地址、partition、心跳、批量、AI 网关地址、S3 地址）
ai-configmap.yml      AI 服务非敏感配置（LLM provider / model / 超时）
secret.example.yml    敏感配置示例（DB_PASSWORD / SECRET_KEY / AI 服务间凭证 / S3 凭证），复制为 secret.yml 使用
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

Backend 流水线通过一次 `mvn verify` 完成单测、Testcontainers 集成测试、JaCoCo
报告、90% 行覆盖率检查及 JAR 打包；随后的 Coverage 阶段只发布报告。
Maven 仓库使用节点本地 `hostPath`，宿主机路径为
`/var/cache/jenkins/maven/repository`，容器挂载路径为 `/var/cache/maven/repository`，
供同一节点上的 Java 项目和分支共享，不再按项目或任务分目录，也不使用 NFS。
Kubelet 通过 `DirectoryOrCreate` 创建目录，Maven 容器沿用镜像默认的 root 用户写入；
构建节点需允许该 hostPath，并将该路径保留在本地磁盘上。
所有接入项目需统一挂载上述 hostPath、使用兼容的 Maven 3.9.x，并在 Maven 命令中
传入以下参数，确保不同进程使用相同的文件锁协调共享仓库的读写：

```sh
-Dmaven.repo.local=/var/cache/maven/repository \
-Daether.syncContext.named.factory=file-lock \
-Daether.syncContext.named.nameMapper=file-gav
```

`disableConcurrentBuilds()` 只串行化同一 Jenkins 任务，跨项目的仓库并发由上述文件锁
处理。缓存跨 Pod 保留；每个节点首次使用时需要下载依赖，其他项目可复用已有依赖。
`cleanWs()` 不清除此缓存；维护清理应在所有使用该节点缓存的构建停止后进行。
执行 `mvn install` 的项目应另行隔离本地产物，避免同坐标的分支产物互相覆盖；
EventPulse 使用 `verify`，不会向共享仓库安装项目产物。
构建日志输出所用仓库路径和 Maven verify 耗时。旧的项目专用缓存不会自动迁移或删除。

Backend Jenkins 控制台保留 Maven 阶段进度、测试统计和失败摘要。Surefire 将测试的
stdout/stderr 写入 `target/surefire-reports/*-output.txt`，成功用例的 XML 不再重复
嵌入这些输出。无论测试成功或失败，已有测试报告都会压缩为构建附件
`backend/target/backend-test-logs.tar.gz`，可从 Jenkins 的 Artifacts 下载排查；
JUnit 测试结果仍正常发布，测试或覆盖率失败仍阻止后续发布。
CI 通过 `SQL_LOG_LEVEL=WARN` 关闭逐条 SQL DEBUG 输出；Kafka 不可用测试仅将
`AdminMetadataManager` 的重复重连 INFO 日志调到 WARN，保留警告、错误和断言。

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
| backend | `api-deployment.yaml`、`worker-deployment.yaml`、`seeder-job.yaml` |
| frontend | `frontend-deployment.yaml` |
| ai-service | `ai-service-deployment.yaml` |

Jenkins 需能访问与 nightdeal 相同的 `k3s-home-write` 凭据（Username with password，
密码为拥有 k3s-home Contents 写权限的 GitHub token）。镜像推送继续使用 `ghcr-token`。
`scripts/update-k3s-home.sh` 直接使用刚推送的 `FULL_IMAGE`，只替换对应镜像行；
版本未变化时不创建提交，目标清单缺失或镜像不匹配时让构建失败。三个任务同时推送
发生冲突时，会从远端最新 main 重新应用本服务的修改，最多尝试五次，不强推。

API、Worker 和 Seeder 在同一次 Git 提交中更新为同一个后端镜像，保证三者携带
一致的 Flyway 迁移文件；任一清单缺失或镜像匹配异常时，整个更新失败，不推送
部分修改。Job 名保持 `eventpulse-seeder`；已创建 Job 的 Pod 模板不可变，镜像
变化后由 `k3s-home/apps/eventpulse/seeder-job.yaml` 上的资源级注解
`argocd.argoproj.io/sync-options: Force=true,Replace=true` 让 Argo CD 删除旧 Job 并重建。
该 Job 仍在 wave 0，数据库就绪后执行、成功后才更新 wave 10 的应用；再次运行时
`seed_runs` 会跳过已完成的播种。直接使用 `kubectl apply` 则仍需手动删除旧 Job。
GitOps 脚本只更新镜像并保留上述注解，不修改数据库迁移历史。集成测试使用临时本地仓库，
不访问 GitHub：

```bash
python3 -m unittest discover -s scripts/tests -v
```

---

## 🔨 本地开发

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

---

## 🧪 测试

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
| `WalletLedgerMigrationIT` | V2 迁移两阶段验证：老账户期初记录不改余额、余额可由期初加流水核对、迁移前的老订单仍能正常退款记账 |
| `AiGatewayServiceTest` / `AiServiceClientTest` / `InternalServiceInterceptorTest` | AI 网关限流、活动复核、编造 ID 过滤、服务间认证 |
| `AiRetentionWorkerTest` | AI 会话保留期清理的批量、顺序（先消息后会话）与开关 |
| `MediaServiceTest` / `S3MediaStorageTest` / `MediaPurgeWorkerTest` | 图片上传校验、key 生成、DB 失败补偿删除、读取 404/503 映射、软删除不碰对象、S3 异常翻译、清理任务语义 |
| `MediaS3ProfileWiringIT` / `MediaS3WorkerProfileWiringIT` / `MediaS3SeederProfileWiringIT` | S3 启用后 api / worker / seeder 装配与启动兼容性（S3Client 构造不联网），默认回落本地磁盘 |
| `S3LiveMediaStorageIT` | 真实 S3 读写删连通性（`MEDIA_S3_LIVE_TEST=true` 才运行，只用 `__eventpulse-selftest/` 临时前缀并自清理） |

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

---

## 🤝 贡献

欢迎贡献！流程与现有提交习惯保持一致（Conventional Commits + PR）：

1. Fork 本仓库（或在仓库内直接从 `main` 拉出分支）：
   ```bash
   git checkout -b feat/your-feature
   ```
2. 提交更改，提交信息用 Conventional Commits（`feat:` / `fix:` / `ci:` / `docs:` + 中文描述）：
   ```bash
   git commit -m 'feat: 支持按城市筛选活动'
   ```
3. 推送分支：
   ```bash
   git push origin feat/your-feature
   ```
4. 打开 Pull Request，CI 全绿后合并。

PR 会跑 GitHub Actions（gitleaks 密钥扫描、依赖检查、后端 Testcontainers 集成测试、
前端 ESLint + Vitest + 类型检查 + 构建 + Playwright、AI 服务 pytest、Compose 与 K8s
配置校验）；`main` 分支推送成功后由 Jenkins 发布 GHCR 镜像并更新 k3s 配置仓库。
后端改动请保证 `make test` 全绿（含 JaCoCo 90% 行覆盖率门槛），前端改动请保证
`make test-frontend` 全绿。

---

## 📝 许可证

本项目尚未声明开源许可证，默认保留所有权利。如需引用或复用代码，请先通过
[Issues](https://github.com/kaiwenyao/eventpulse/issues) 联系作者。

---

## 📧 联系

- **GitHub Issues**：<https://github.com/kaiwenyao/eventpulse/issues>
- **作者主页**：<https://github.com/kaiwenyao>

---

Made with ❤️ by [kaiwenyao](https://github.com/kaiwenyao)