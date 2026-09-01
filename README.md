# EventPulse

## 运行

本机需要 Docker Desktop（macOS / Windows）或 Docker Engine + Compose v2（Linux）。

先清掉可能残留的 Compose 环境和 Testcontainers 容器，再启动：

```bash
cp .env.example .env
make down-v                  # 停栈并删数据卷
make testcontainers-cleanup  # 清掉 Testcontainers 残留容器
make up                      # docker compose up -d --build
make ps                      # postgres / redis / kafka / backend / frontend 均应 healthy
```

第一次会构建镜像，大约几分钟。backend 健康检查有 60s `start_period`（Flyway + demo seed）。

| 端口 | 服务 |
| --- | --- |
| 3000 | 前端（`/api` 反代到 backend） |
| 8080 | 后端 API |
| 5432 | PostgreSQL |
| 6379 | Redis |
| 9092 | Kafka |

```bash
curl -s http://localhost:8080/actuator/health
# 期望：{"status":"UP", ...}
```

打开 http://localhost:3000

`SECRET_KEY` 用于 JWT 签名，`DB_PASSWORD` 是 Postgres 密码；demo 可直接用 `.env.example` 默认值。

`demo` profile 启动时播种：

| 角色 | 邮箱 | 密码 |
| --- | --- | --- |
| 普通用户 | `user@eventpulse.dev` | `User123456` |
| 主办方 | `organiser@eventpulse.dev` | `Organiser123456` |

```bash
make logs        # 跟随 backend 日志
make down        # 停容器，保留数据卷
make down-v      # 停容器并删数据卷；下次 make up 会重新 seed
```

不要同时 `make up` 和本机 `spring-boot:run`：两边都会占 8080。

## 本地开发

本机需要 JDK 21、Maven、Node.js。同样先清残留，再只起基础设施：

```bash
make down-v
make testcontainers-cleanup
make infra       # 只启动 postgres / kafka
```

```bash
# 终端 1
mvn -pl backend spring-boot:run -Dspring-boot.run.profiles=demo
```

```bash
# 终端 2
cd frontend && npm ci && npm run dev
```

前端：http://localhost:5173

### 前端结构

```
frontend/src
├─ App.tsx           路由与应用外壳（顶栏 / Toast / 页脚）
├─ api.ts  auth.tsx  网络层与会话
├─ types.ts          与后端 DTO 对应的视图模型、分类与状态字典
├─ lib/              纯函数工具（ISO ↔ datetime-local 转换、相对时间）
├─ ui/               设计系统原语：Field / Badges / Modal / Toast / Skeleton / Icons
├─ components/       跨页面组件（顶栏、活动票卡）
├─ pages/            观众端页面（发现、详情、登录、预订、收藏、消息）
├─ organiser/        主办方控制台（概览、活动表格、发布表单、生命周期、参与者、数据）
└─ styles/           按关注点拆分的样式表，由 styles.css 汇总导入
```

发布活动的表单逻辑（默认值、字段校验、请求体映射）集中在
`organiser/eventForm.ts`，是纯函数，单独做了单元测试；页面组件只负责把状态接到
表单控件上。

## 测试

先清掉可能残留的 Compose 环境和 Testcontainers 容器：

```bash
make down-v                  # 停栈并删数据卷
make testcontainers-cleanup  # 清掉 Testcontainers 残留容器
```

然后 `make up`，等栈 healthy 之后再冒烟（需要本机 `curl` 和 `python3`）：

```bash
make smoke                 # 默认打 http://localhost:8080
# BASE_URL=http://localhost:3000/api  bash scripts/smoke-test.sh
```

全部 PASS 会打印 `SMOKE TEST: ALL GREEN`。

不依赖整栈（后端测试会拉 Testcontainers，本机需要 Docker）：

```bash
make test                  # 后端 mvn verify
make test-frontend         # ESLint + Vitest + Playwright
make test-all              # 上面两层
```
