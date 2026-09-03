# EventPulse 分布式部署改造计划

> 状态：后端分布式部分已实施；Python AI 服务扩展待实施
> 日期：2026-09-02

## 1. 改造目标

本次改造后，同一个 Spring Boot 镜像可以用三种方式运行：

```text
                                  Kubernetes
                                      │
                 ┌────────────────────┼────────────────────┐
                 │                    │                    │
             基础设施               常驻服务              一次性任务
                 │                    │                    │
       ┌─────────┼─────────┐     ┌────┴────┐               │
       ▼         ▼         ▼     ▼         ▼               ▼
  PostgreSQL   Redis   Kafka 集群 API       Worker          Seeder
                                 │          │               │
                              长期运行    长期运行        执行完退出
```

三个运行角色使用同一个镜像，例如：

```text
eventpulse/backend:v1.0
```

但启动时使用不同的 Spring Profile：

```text
api       只处理 HTTP 请求和 SSE 连接
worker    处理 Kafka、Outbox 和活动生命周期
seeder    初始化演示数据，完成后退出
```

这次计划只处理以下内容：

1. 移除业务级 JVM 内存状态。
2. 完整改造 SSE。
3. 改造后台任务，使其可以独立运行。
4. 拆分 API、Worker、Seeder 三种角色。
5. 将 Seeder 改为 Kubernetes Job。
6. 修改 Docker Compose 和 Kubernetes 部署配置。
7. 将 Kafka 从单节点改造成 3 节点学习集群。
8. 将 Python AI 服务作为独立常驻服务接入 API。

图片存储不在本次范围内。当前没有使用 media 功能，因此暂不改造。

PostgreSQL 和 Redis 在本次学习项目中继续使用单节点，不做集群和分片。

API、Worker、Seeder 继续共用 Spring Boot 镜像；Python AI 服务使用单独镜像，例如 `eventpulse/ai-service:v1.0`。第一版部署一个 AI Service 副本，不要求它与 Kafka 一样做集群。

---

## 2. 完成后的职责划分

### 2.1 API

API 负责：

- 登录、活动、预订、票据、收藏和通知等 HTTP 接口。
- 建立和维护浏览器的 SSE 长连接。
- 从 Redis 收到“有新状态”的提醒后，将提醒推送给连接在当前实例上的浏览器。

API 不负责：

- 扫描和发送 Outbox。
- 消费 Kafka 消息。
- 定时改变活动状态。
- 初始化演示数据。

API 可以部署多个副本，由负载均衡器分配请求。

### 2.2 Worker

Worker 负责：

- 将 Outbox 中待发送的消息发到 Kafka。
- 消费 Kafka 消息，写入通知和统计数据。
- 执行活动状态的定时更新。
- 完成业务处理后，通过 Redis 通知所有 API 实例。

Worker 不提供业务 HTTP 接口。它可以保留健康检查端口，也可以使用 Kubernetes 对进程进行检查。

### 2.3 Seeder

Seeder 负责：

- 初始化演示账号、活动、订单、票据、收藏和统计数据。
- 执行完成后主动退出。
- 初始化失败时返回非零退出码，让 Kubernetes 将 Job 标记为失败。

Seeder 不启动 Web 服务，不启动 Kafka consumer，也不启动任何定时任务。

### 2.4 Python AI Service

Python AI Service 负责：

- 通过 LangChain 调用外部 LLM。
- 为主办方生成可审核的活动文案建议。
- 运行活动发现 Agent，并调用 Spring Boot 提供的只读工具接口。
- 返回经过 Pydantic 校验的固定数据结构。

Python AI Service 不直接连接 PostgreSQL，不保存进程内会话，也不提供公网入口。浏览器只访问 Spring Boot API。

---

## 3. 第一部分：消除业务级 JVM 内存状态

### 3.1 删除本地热门缓存

当前 `PlatformService` 使用 `ConcurrentHashMap` 保存热门活动。每个 API 实例都有自己的一份，因此不同实例可能看到不同结果。

计划修改：

1. 删除 `popularCache` 和 `CacheEntry`。
2. 热门活动只缓存到 Redis。
3. Redis 中没有缓存时查询 PostgreSQL，然后写入 Redis。
4. Redis 暂时不可用时直接查询 PostgreSQL，不再把结果保存在当前 JVM 中。
5. 活动发布、取消、归档或售票数量变化后，删除对应的热门缓存，使下次请求重新计算。

目标流程：

```text
请求热门活动
      │
      ▼
读取 Redis
  ├── 有数据：直接返回
  └── 无数据或 Redis 暂时不可用
              │
              ▼
        查询 PostgreSQL
              │
              ├── Redis 可用：写入缓存
              └── Redis 不可用：直接返回数据库结果
```

需要修改的主要文件：

- `backend/src/main/java/dev/kaiwen/eventpulse/service/PlatformService.java`
- `backend/src/main/java/dev/kaiwen/eventpulse/config/RedisConfig.java`
- `backend/src/main/resources/application.yml`

验收标准：

- 代码中不再存在 `popularCache`。
- 请求被分配到不同 API 实例时，热门活动结果一致。
- Redis 停止后接口仍能从 PostgreSQL 返回结果。
- API 重启不会造成业务数据丢失。

### 3.2 替换 JVM 内的统计计数

当前 `cacheFallbacks` 是一个普通 Java 字段。多实例部署后，每个实例只知道自己的数字，而且并发增加时可能丢失计数。

计划修改：

1. 删除 `cacheFallbacks` 字段。
2. 使用 Spring Boot 自带的 Micrometer Counter 记录缓存降级次数。
3. 主办方 Dashboard 不再直接读取某个 JVM 的计数。
4. 如果页面必须展示该数字，则从监控系统读取聚合结果；第一版可以先从业务 Dashboard 中移除该字段。

需要修改的主要文件：

- `backend/src/main/java/dev/kaiwen/eventpulse/service/PlatformService.java`
- `backend/src/main/resources/application.yml`
- 相关 Dashboard DTO 和前端展示代码

验收标准：

- `PlatformService` 不再保存可变化的统计字段。
- 多个 API 实例产生的缓存降级次数可以在监控系统中分别查看和汇总。

### 3.3 整理当前用户上下文

当前用户 ID 和角色保存在 `ThreadLocal` 中。普通请求结束后会清理，但 SSE 是异步请求：建立 SSE 后，请求线程会先回到线程池，现有拦截器不能保证此时立即清理用户信息。

本次采用分两步处理的方式：

#### 第一步：先消除泄漏风险

1. `JwtInterceptor` 改为实现 `AsyncHandlerInterceptor`。
2. 在 `afterConcurrentHandlingStarted()` 中调用 `BaseContext.clear()`。
3. 保留 `afterCompletion()` 清理，覆盖普通请求和 SSE 最终结束的情况。
4. 在 `preHandle()` 开始时先清理一次旧上下文，再解析当前请求的 JWT。

#### 第二步：逐步减少静态上下文的使用

1. Controller 从请求中取得当前用户 ID。
2. Service 方法通过参数接收 userId 和 role。
3. 逐步删除 Service 对静态 `BaseContext` 的依赖。
4. 所有 Service 完成迁移后，再删除 `BaseContext`。

第二步不要求一次改完所有业务接口，可以按模块逐步完成；但第一步必须在 SSE 上线前完成。

需要修改的主要文件：

- `backend/src/main/java/dev/kaiwen/eventpulse/interceptor/JwtInterceptor.java`
- `backend/src/main/java/dev/kaiwen/eventpulse/common/BaseContext.java`
- 使用 `BaseContext` 的 Controller 和 Service

验收标准：

- SSE 建立后，原请求线程中不保留用户 ID 和角色。
- 公共接口不会读取到上一个请求的用户身份。
- 增加一个复用同一线程的测试，验证用户上下文不会串到下一个请求。

---

## 4. 第二部分：SSE 详细改造方案

### 4.1 SSE 是什么

SSE 的全称是 Server-Sent Events，可以理解为“服务器给浏览器保持一条只向浏览器发送消息的 HTTP 长连接”。

普通 HTTP 请求是：

```text
浏览器发起请求 → 服务器返回结果 → 连接结束
```

SSE 是：

```text
浏览器发起请求 → 服务器保持连接
                         │
                         ├── 推送状态变化
                         ├── 推送另一条状态变化
                         └── 定期发送心跳，避免连接被关闭
```

EventPulse 适合用 SSE 的场景是：用户打开订单详情页后，订单、票据或活动状态发生变化，页面不需要不停刷新就能收到提醒。

SSE 不是数据的最终保存位置。即使用户没有打开页面，通知和订单状态仍然必须保存在 PostgreSQL 中。

### 4.2 为什么当前实现不能用于多实例

当前实现把 `SseEmitter` 保存在当前 JVM 的 Map 中：

```text
浏览器 ──SSE──> API 实例 A
                    │
                    └── emitter 只存在实例 A 的内存里
```

Kafka 消息由 Worker 处理后，Worker 并不知道用户连接在哪个 API 实例。即使另一个 API 实例 B 知道状态变化，B 也无法使用 A 内存中的连接。

另外，当前 `PlatformService.emit()` 没有被业务流程调用，前端也没有真正建立 SSE 连接，因此现有 SSE 只是接口框架，还没有形成完整链路。

### 4.3 目标 SSE 架构

本次采用“PostgreSQL 保存结果、Redis 广播提醒、API 保存临时连接”的结构：

```text
业务操作
   │
   ▼
PostgreSQL + Outbox
   │
   ▼
Kafka
   │
   ▼
Worker 完成通知和状态处理
   │
   ├── 将最终结果写入 PostgreSQL
   │
   └── 数据库提交成功后，向 Redis 发布一条轻量提醒
                  │
          ┌───────┴────────┐
          ▼                ▼
       API 实例 A       API 实例 B
          │                │
    查找本机连接       查找本机连接
          │                │
       浏览器 A          浏览器 B
```

Redis 中发送的只是“有变化，请刷新”的提醒，不承担可靠保存职责。如果 Redis 短暂中断，浏览器重新连接后仍然可以通过 REST 接口从 PostgreSQL 取得最新状态。

Redis 提醒必须在数据库事务提交成功后再发送，不能在事务中途发送。否则浏览器收到提醒后立即查询，可能仍然看到旧数据；如果数据库随后回滚，还会出现“页面收到提醒，但实际没有变化”的情况。实现时由事务提交后的回调负责发布提醒。

### 4.4 为什么 API 内仍然会有一个连接 Map

网络连接本身必须属于某一台服务器。一个已经连接到 API 实例 A 的浏览器连接，不能直接存进 Redis，也不能由 API 实例 B 接管。

因此，API 内仍然需要保存临时的连接对象，但这个 Map 只能保存：

- 当前有哪些浏览器连接在本实例上。
- 如何向这些连接写入消息。

它不能保存：

- 订单最终状态。
- 用户是否已经收到过某条重要通知。
- 任何在 API 重启后无法恢复的业务结果。

这类连接信息属于临时网络资源，不属于业务状态。API 重启后连接会断开，浏览器自动重连，再从 PostgreSQL 获取最新状态即可。

### 4.5 SSE 连接管理

当前一个 bookingId 只对应一个 `SseEmitter`，用户打开两个标签页时，后打开的连接会覆盖前一个连接。

计划改为：

```text
bookingId
    └── connectionId
            └── SseEmitter
```

每个连接生成独立的 connectionId。连接完成、超时或出错时，只删除自己，不影响同一个订单的其他连接。

具体处理：

1. 新建 `SseConnectionRegistry`，专门管理本实例的连接。
2. `PlatformService` 不再直接保存 emitter Map。
3. 注册 `onCompletion`、`onTimeout` 和 `onError` 三种清理回调。
4. API 每 20～30 秒发送一次心跳。
5. API 停机时主动关闭本实例的连接，让浏览器尽快重连。
6. 限制单个用户和单个订单允许建立的连接数，防止连接无限增长。

建议的新组件：

```text
SseConnectionRegistry    管理本机连接
SseEventSubscriber       接收 Redis 广播
SseNotificationService   将消息发给本机匹配的连接
```

### 4.6 SSE 消息内容

SSE 不直接传完整业务对象，只传一个轻量提醒，例如：

```json
{
  "eventId": "01J...",
  "type": "BOOKING_UPDATED",
  "bookingId": 123,
  "occurredAt": "2026-09-02T10:20:30Z"
}
```

前端收到提醒后，重新调用订单详情或通知接口。这样可以避免 SSE 消息与数据库中的最终结果不一致。

`eventId` 用于前端忽略重复提醒。同一条提醒收到两次不会重复改变业务数据，只会最多多刷新一次页面。

### 4.7 SSE 鉴权

订阅订单事件前必须检查：

1. 用户已经登录。
2. bookingId 对应的订单存在。
3. 订单属于当前用户；主办方只能订阅自己活动相关的数据。

不再把长期 JWT 放进 URL 的 `access_token` 参数，因为 URL 可能出现在代理日志和浏览器记录中。

前端改用支持请求头的 SSE 客户端，通过 `Authorization: Bearer ...` 发送 JWT。实现时可以使用 fetch-based SSE 客户端，页面关闭时使用 `AbortController` 主动断开。

### 4.8 断线与数据补偿

SSE 连接可能因为 API 发布、网络变化或负载均衡器超时而中断，因此前端必须按以下方式处理：

1. 页面打开时先用 REST 获取订单最新状态。
2. 然后建立 SSE 连接。
3. SSE 收到提醒后，再用 REST 刷新。
4. SSE 断开后逐步延长等待时间并自动重连。
5. 重连成功后再次调用 REST，补上断线期间发生的变化。

这样即使某条 Redis 提醒没有送达，也不会造成业务数据丢失。

### 4.9 SSE 开发顺序

1. 修复异步请求的 `ThreadLocal` 清理。
2. 给 SSE 接口增加订单所有权检查。
3. 新建 `SseConnectionRegistry`。
4. 增加 Redis 发布和订阅组件。
5. Worker 完成数据库事务后发布提醒。
6. API 收到提醒后向本机连接发送 SSE。
7. 前端订单详情页接入 SSE，并实现自动重连。
8. 增加心跳、连接上限和停机清理。

### 4.10 SSE 验收标准

- 浏览器连接到 API A，消息由任意 Worker 处理时，浏览器都能收到提醒。
- 两个浏览器标签页同时打开同一订单时，两个页面都能收到提醒。
- 用户不能订阅其他用户的订单。
- API A 重启后浏览器能够自动连接到 API B，并取得最新状态。
- Redis 短暂停止时，REST 功能不受影响；Redis 恢复后可以继续推送。
- SSE 断线期间发生的状态变化可以通过重新查询 REST 补回来。
- API 实例退出后，其连接 Map 会被清空，不影响任何最终业务数据。

---

## 5. 第三部分：后台任务改造

### 5.1 将后台任务移出 API

当前后台任务和 HTTP API 在同一个进程中启动。API 扩容后，每个实例都会运行相同任务。

计划将以下内容只放在 `worker` Profile 中：

- `OutboxRelay`
- `BookingConsumer`
- 活动生命周期更新任务
- Kafka 错误处理和 Worker 专用配置

`api` 和 `seeder` Profile 不创建这些 Bean，也不启动调度线程。

### 5.2 Outbox 多 Worker 处理

当前多个 Worker 会同时读取同一批 Outbox 记录，然后重复发送。

建议分两步上线：

#### 第一步：拆分角色时先部署一个 Worker

- 先保证 API 可以独立扩容。
- Worker 暂时保持一个副本。
- 完成多 Worker 抢占机制以后，再增加 Worker 副本数。

#### 第二步：增加 Outbox 领取机制

给 Outbox 增加以下字段：

```text
message_key      稳定的业务标识，例如 booking:123
claimed_by       哪个 Worker 正在处理
claimed_until    最晚处理到什么时间
```

`message_key` 和现有 `dedup_key` 用途不同：

- `message_key` 保证同一个订单的消息进入 Kafka 的同一个 partition，并保持先后顺序。
- `dedup_key` 判断某一条具体消息是否已经处理过，用来拦截重复消费。

处理流程：

```text
Worker 尝试领取一批消息
          │
          ├── 已被其他 Worker 领取：跳过
          └── 领取成功
                  │
                  ▼
              发送 Kafka
             ├── 成功：标记已发布并清除领取信息
             └── 失败：记录错误并释放，或等待领取时间到期
```

领取记录必须通过一条数据库操作完成，避免两个 Worker 同时认为自己领取成功。Worker 意外退出后，`claimed_until` 到期，其他 Worker 可以继续处理，不会永久卡住。

领取时还要增加一条顺序规则：同一个 `message_key` 如果存在更早且尚未发布的消息，后面的消息暂时不能领取。例如同一订单的“创建”尚未发出时，不能先发送“取消”。不同订单之间仍然可以由多个 Worker 并行处理。

还需要保留现有的 consumer 幂等表，因为以下情况仍可能产生重复消息：Kafka 已经收到，但 Worker 在填写 `published_at` 之前退出。

需要修改的主要文件：

- `backend/src/main/java/dev/kaiwen/eventpulse/outbox/OutboxRelay.java`
- `backend/src/main/java/dev/kaiwen/eventpulse/outbox/OutboxStatusService.java`
- `backend/src/main/java/dev/kaiwen/eventpulse/repository/OutboxRepository.java`
- `backend/src/main/java/dev/kaiwen/eventpulse/entity/OutboxEvent.java`
- 新的 Flyway migration

验收标准：

- 两个 Worker 同时运行时，同一条消息不会同时被两个 Worker 领取。
- Worker 领取后被强制终止，领取超时后另一 Worker 能继续处理。
- Kafka 已收到但数据库尚未标记时发生重试，不会重复创建通知或统计。

### 5.3 活动生命周期任务

当前任务先查询活动，再修改 Java 对象。多个 Worker 同时执行时可能发生版本冲突。

计划改成两条数据库条件更新：

```text
将开始时间已到、状态仍为 PUBLISHED 的活动改为 ONGOING
将结束时间已到、状态仍为 ONGOING 的活动改为 FINISHED
```

数据库只更新仍满足条件的记录。第二个 Worker 再执行时会更新 0 行，不会覆盖新状态。

同时将该逻辑从 `PlatformService` 移到独立的 `EventLifecycleWorker`，仅在 `worker` Profile 中启用。

验收标准：

- 多个 Worker 同时执行时不出现乐观锁异常。
- 活动只会按照允许的方向改变状态。
- 每次执行记录扫描时间、更新数量和失败次数。

### 5.4 Kafka consumer 扩容

多个 Worker 使用同一个 consumer group。Kafka 会把不同 partition 分配给不同 Worker。

当前业务 Topic 只有一个 partition，所以即使启动多个 Worker，也只有一个 Worker 会消费该 Topic。

计划修改：

1. 将 partition 数改为可配置项。
2. 本地开发至少使用 3 个 partition，验证多 Worker 分配。
3. 发送消息时使用稳定的业务 key，确保同一订单的消息进入同一个 partition。
4. `dedupKey` 继续用于判断消息是否已经处理，不再同时承担消息分区用途。

验收标准：

- 两个 Worker 能同时消费不同 partition。
- 同一订单的创建、取消等消息保持顺序。
- 重放消息不会重复生成通知或统计。

---

## 6. 第四部分：Kafka 集群分布式改造

### 6.1 学习目标

当前 Docker Compose 中只有一个 Kafka 节点。这个节点停止后，消息发送和消费都会停止，也无法演示副本切换。

本次将 Kafka 改成 3 节点集群，重点学习：

- 多个 Kafka 节点如何组成一个集群。
- 一条消息如何在多个节点上保存副本。
- Topic partition 如何分配给多个 Worker。
- 一个 Kafka 节点停止后，系统如何继续工作。
- 多数 Kafka 节点不可用时，Outbox 如何保留消息并等待恢复。

这部分只建设单个机房内的学习集群，不做跨城市、跨地域或自动扩缩容。

### 6.2 Kafka 目标结构

使用 KRaft 模式运行 3 个 Kafka 节点，每个节点同时承担 broker 和 controller 角色：

```text
                     Kafka 集群
        ┌────────────────┼────────────────┐
        │                │                │
        ▼                ▼                ▼
   Kafka 节点 1     Kafka 节点 2     Kafka 节点 3
 broker+controller broker+controller broker+controller
        │                │                │
        └────────────────┼────────────────┘
                         │
                    独立数据卷
```

三个 controller 通过投票决定集群状态。只要还有两个 controller 正常通信，集群仍然可以选出负责人。

每个 Kafka 节点必须具有：

- 唯一的 node ID。
- 独立的数据目录和持久卷。
- 能被其他 Kafka 节点访问的内部地址。
- 能被 API 或 Worker 访问的客户端地址。
- 健康检查。

### 6.3 Topic 和副本设置

业务 Topic 与 DLT Topic 统一采用：

```text
Topic                     partitions    replication factor
booking-events                 3                 3
booking-events.DLT             3                 3
```

同时设置：

```text
min.insync.replicas = 2
producer acks = all
producer enable.idempotence = true
default.replication.factor = 3
offsets.topic.replication.factor = 3
transaction.state.log.replication.factor = 3
transaction.state.log.min.isr = 2
auto.create.topics.enable = false
```

这些配置的含义是：

- 每条消息在 3 个 Kafka 节点上都有副本。
- 至少 2 个同步副本确认后，Producer 才认为发送成功。
- 停止 1 个 Kafka 节点后，剩余 2 个节点仍然可以继续接收消息。
- 如果只剩 1 个同步节点，Producer 不会假装成功；消息继续留在 Outbox，等待 Kafka 恢复。
- consumer group 的消费进度也使用 3 个副本保存，不会只依赖一个 Kafka 节点。
- 禁止自动创建 Topic，避免因为 Topic 名称拼错而生成一个副本数不正确的新 Topic。

`booking-events` 和 DLT 的 partition 数保持一致，方便失败消息保留原 partition 信息。

### 6.4 消息 key、partition 和 Worker 的关系

Kafka 按消息 key 选择 partition。为了保证同一个订单的消息顺序，所有与同一订单有关的消息都使用同一个 `message_key`：

```text
booking:123 → partition 1
booking:456 → partition 2
booking:789 → partition 0
```

同一个 consumer group 中，一个 partition 同一时间只交给一个 Worker：

```text
partition 0 → Worker A
partition 1 → Worker B
partition 2 → Worker A
```

3 个 partition 最多允许 3 个 Worker 同时消费。如果启动第 4 个 Worker，它会等待其他 Worker 下线或发生重新分配。

`message_key` 只负责顺序，`dedup_key` 继续负责识别重复消息，两者不能混用。

### 6.5 应用连接 Kafka 集群

只有 Worker 需要连接 Kafka。Worker 不再只配置一个 Kafka 地址，而是使用多个初始地址：

```text
KAFKA_BOOTSTRAP=kafka-1:9092,kafka-2:9092,kafka-3:9092
```

这些地址用于第一次连接。连接成功后，Kafka client 会自动发现当前集群中的其他节点。

需要修改：

1. Topic 改由部署配置统一创建：Compose 使用 Topic 初始化任务，Kubernetes 使用 Kafka Topic 资源。
2. 现有 `KafkaTopicConfig` 不再作为生产环境 Topic 的创建者，避免应用配置和部署配置互相覆盖。
3. Worker 的 Producer 和 Consumer 都使用相同的 Kafka 集群地址。
4. Consumer group 保持当前的 `eventpulse`，升级时不随意改名，避免旧消息被当成新消息重新处理。
5. Producer 继续等待 Kafka 明确确认成功后，才将 Outbox 标记为已发布。
6. Kafka 集群暂时不可用时，Worker 记录错误并在稍后重试，不退出整个进程。

### 6.6 已有 Topic 的处理

修改代码中的 Topic 配置不会自动把已经存在的 Topic 从 1 个副本变成 3 个副本。

本地开发环境采用简单方式：

1. 停止旧环境。
2. 删除旧 Kafka 数据卷。
3. 使用新配置重新创建 3 节点集群和 Topic。

如果需要保留已有 Kafka 数据，则编写副本重新分配脚本，将现有 partition 逐步复制到 3 个节点，不能直接删除 Topic。

### 6.7 Kafka 故障时的预期行为

| 场景 | 预期结果 |
| --- | --- |
| 3 个节点正常 | 正常发送和消费 |
| 停止任意 1 个节点 | 重新选择 partition leader，继续发送和消费 |
| 恢复被停止的节点 | 自动追赶缺少的消息副本 |
| 同时停止 2 个节点 | 新消息不能满足两个副本确认，Outbox 保留待发送记录 |
| 恢复到至少 2 个节点 | Worker 重新发送 Outbox，消费继续 |
| Consumer 重启 | consumer group 重新分配 partition |

三节点都运行在同一台开发电脑上时，只能模拟“Kafka 进程或容器停止”，不能抵抗整台电脑断电。这对学习和自动化测试足够，但不能称为跨机器高可用。

### 6.8 Kafka 验收标准

- Kafka 集群包含 3 个正常节点。
- `booking-events` 和 DLT 都有 3 个 partition、3 个副本。
- 每个 partition 的副本分布在不同 Kafka 节点。
- 两个 Worker 可以同时消费不同 partition。
- 同一订单的消息始终进入同一个 partition。
- 停止任意一个 Kafka 节点后仍能创建订单、发送消息并生成通知。
- 同时停止两个 Kafka 节点时，Outbox 不会错误标记成功。
- 恢复 Kafka 后，积压的 Outbox 消息可以继续发送。
- 重试和 Kafka 副本切换不会造成重复通知或重复统计。

---

## 7. 第五部分：同一镜像支持三种运行角色

### 7.1 Profile 设计

新增配置文件：

```text
application-api.yml
application-worker.yml
application-seeder.yml
```

启动方式：

```bash
java -jar app.jar --spring.profiles.active=api
java -jar app.jar --spring.profiles.active=worker
java -jar app.jar --spring.profiles.active=seeder
```

Spring Profile 是运行角色的唯一来源。角色组件优先直接使用 `@Profile("api")`、`@Profile("worker")` 或 `@Profile("seeder")` 控制是否创建，不再额外增加一套容易冲突的角色开关。测试需要逐一验证三个 Profile 实际加载了哪些组件，避免 API 意外启动 Worker，或者 Seeder 意外启动 Web 服务。

业务 Controller 只在 `api` Profile 中创建；Outbox、Kafka consumer 和生命周期组件只在 `worker` Profile 中创建；Seeder 只在 `seeder` Profile 中创建。

### 7.2 API Profile

```text
Web Server                 开启
Controller                 开启
SSE                        开启
Redis SSE 订阅             开启
Kafka consumer             关闭
Outbox 定时发布            关闭
活动生命周期任务           关闭
Seeder                     关闭
```

### 7.3 Worker Profile

```text
业务 HTTP Controller       关闭
Kafka consumer             开启
Outbox 定时发布            开启
活动生命周期任务           开启
Redis SSE 提醒发布         开启
Seeder                     关闭
```

Worker 是否保留一个只用于健康检查的 Web 端口，在实现时选择以下方案之一：

- 保留 Actuator 端口，仅暴露 `/actuator/health`。
- 完全关闭 Web Server，由 Kubernetes 使用进程和自定义健康状态检查。

为了部署和排查方便，第一版建议保留独立的 Actuator 端口。

### 7.4 Seeder Profile

```text
Web Server                 关闭
Kafka consumer             关闭
Outbox 定时发布            关闭
活动生命周期任务           关闭
Redis SSE                  关闭
Seeder                     开启
```

Seeder Runner 只负责调用一个带事务的 `SeederService`，不要把所有数据库逻辑和退出逻辑写在同一个类里：

```java
@Component
@Profile("seeder")
public class DataSeeder implements CommandLineRunner {

    private final SeederService seederService;
    private final ApplicationContext context;

    @Override
    public void run(String... args) {
        int exitCode = runSeed();
        int springExitCode = SpringApplication.exit(context, () -> exitCode);
        System.exit(springExitCode);
    }

    private int runSeed() {
        try {
            seederService.seed();
            return 0;
        }
        catch (Exception e) {
            log.error("Seeder 执行失败", e);
            return 1;
        }
    }
}
```

Seeder 数据写入放在一个数据库事务中：全部成功才提交；中途失败则全部回滚。Seeder 还必须允许 Job 重试，不能因为第一次执行到一半或人工重新运行就生成重复数据。

### 7.5 Seeder 执行记录

建议新增简单的 `seed_runs` 表：

```text
seed_name      初始化任务名称，例如 demo-v1
completed_at   完成时间
```

`seed_name` 使用唯一约束。Seeder 执行时先判断该版本是否已经完成：

- 已完成：打印提示并正常退出。
- 未完成：在事务内完成播种并记录 `demo-v1`。
- 执行失败：事务回滚，不留下“已经完成”的记录。

这样 Kubernetes Job 重试或人工再次运行时不会重复写数据。

---

## 8. 第六部分：Docker Compose 开发环境

### 8.1 服务结构

修改后的 Compose 包含：

```text
postgres
redis
kafka-1
kafka-2
kafka-3
api
worker
seeder
ai-service
frontend
gateway（如果需要统一入口）
```

`api`、`worker`、`seeder` 使用同一个镜像，只覆盖启动 Profile。

示意配置：

```yaml
x-backend-common: &backend-common
  image: eventpulse/backend:v1.0
  environment:
    DB_URL: jdbc:postgresql://postgres:5432/eventpulse
    KAFKA_BOOTSTRAP: kafka-1:9092,kafka-2:9092,kafka-3:9092
    REDIS_HOST: redis
    SECRET_KEY: ${SECRET_KEY}
    AI_SERVICE_URL: http://ai-service:8090

services:
  api:
    <<: *backend-common
    command: ["--spring.profiles.active=api"]

  worker:
    <<: *backend-common
    command: ["--spring.profiles.active=worker"]

  seeder:
    <<: *backend-common
    command: ["--spring.profiles.active=seeder"]
    restart: "no"

  ai-service:
    image: eventpulse/ai-service:v1.0
    environment:
      BACKEND_INTERNAL_URL: http://api:8080
      LLM_MODEL: ${LLM_MODEL}
      LLM_API_KEY: ${LLM_API_KEY}
```

实际 Dockerfile 的 `ENTRYPOINT` 和 Compose `command` 要配合验证，保证参数最终传给 `java -jar app.jar`。

### 8.2 Compose 启动顺序

开发环境启动流程：

```text
启动 PostgreSQL / Redis / 3 个 Kafka 节点
              │
              ▼
等待基础设施健康
              │
              ▼
运行 Seeder，并等待正常退出
              │
              ▼
启动 API / Worker / Frontend
```

Compose 对“一次性任务完成后再启动其他服务”的支持需要使用 `service_completed_successfully`。如果本机 Compose 版本不支持，则由 Makefile 分两步执行：先运行 Seeder，再启动常驻服务。

### 8.3 Compose 多 API 实例

API 容器不再直接固定绑定宿主机 `8080:8080`，否则第二个实例无法启动。统一入口由 gateway 或 frontend Nginx 提供。

开发测试命令目标：

```bash
docker compose up -d --scale api=2 --scale worker=2
```

在 Outbox 领取机制完成前，默认仍使用 `--scale worker=1`。

### 8.4 Compose Kafka 集群

Docker Compose 为三个 Kafka 节点分别配置：

```text
kafka-1   node.id=1   独立数据卷 kafka1data
kafka-2   node.id=2   独立数据卷 kafka2data
kafka-3   node.id=3   独立数据卷 kafka3data
```

三个节点使用同一个 KRaft cluster ID，并在 controller quorum 配置中列出全部三个节点。

宿主机调试端口使用不同端口，避免冲突；容器之间仍统一使用内部的 `9092`。健康检查需要确认对应节点能够回答 Kafka 请求，Worker 要等待至少两个 Kafka 节点可用后再开始处理 Outbox。

Compose 增加一个 Topic 初始化任务，负责创建或检查：

- `booking-events`
- `booking-events.DLT`
- partition 数
- replication factor
- `min.insync.replicas`

---

## 9. 第七部分：Kubernetes 部署

### 9.1 资源清单

计划新增：

```text
deploy/k8s/configmap.yml
deploy/k8s/secret.example.yml
deploy/k8s/api-deployment.yml
deploy/k8s/api-service.yml
deploy/k8s/worker-deployment.yml
deploy/k8s/seeder-job.yml
deploy/k8s/ai-service-deployment.yml
deploy/k8s/ai-service-service.yml
deploy/k8s/ingress.yml
deploy/k8s/kafka/kafka-cluster.yml
deploy/k8s/kafka/kafka-node-pool.yml
deploy/k8s/kafka/kafka-topics.yml
```

同一镜像的使用方式：

```text
Deployment: eventpulse-api
  image: eventpulse/backend:v1.0
  profile: api

Deployment: eventpulse-worker
  image: eventpulse/backend:v1.0
  profile: worker

Job: eventpulse-seeder
  image: eventpulse/backend:v1.0
  profile: seeder

Deployment: eventpulse-ai-service
  image: eventpulse/ai-service:v1.0
  profile: 不使用 Spring Profile
```

### 9.2 API Deployment

- 初始副本数为 2。
- readiness 检查失败后停止接收新流量。
- liveness 检查只判断应用是否卡死，不因为 Redis 短暂故障反复重启 API。
- 设置足够的 `terminationGracePeriodSeconds`，让普通请求结束，并主动关闭 SSE。
- Service 将请求分配给可用 API Pod。
- Ingress 关闭 SSE 响应缓冲，并设置合理的长连接超时。

普通 HTTP 和 SSE 都不依赖固定分配到同一个 Pod。SSE 断线后可以重新连接到其他 Pod。

### 9.3 Worker Deployment

- 第一版副本数为 1。
- Outbox 领取机制完成后增加到 2。
- Worker 收到终止信号后停止领取新任务，等待正在处理的消息结束。
- 健康检查区分“进程活着”和“是否还能正常处理任务”。
- Kafka 暂时不可用时不立即重启整个 Worker，而是记录错误并继续重试。

### 9.4 Seeder Job

示意配置：

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: eventpulse-seeder-v1
spec:
  completions: 1
  parallelism: 1
  backoffLimit: 3
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: seeder
          image: eventpulse/backend:v1.0
          args: ["--spring.profiles.active=seeder"]
```

发布流程必须等待 Seeder Job 成功后，再确认 API 和 Worker 发布完成。Job 失败时停止发布，并保留日志供检查。

Seeder Job 的名称需要包含版本，或者由 Helm hook / CI 发布流水线管理，避免已完成的旧 Job 阻止新版 Seeder 创建。

### 9.5 Kafka 集群

Kubernetes 中不手工拼装普通 StatefulSet，而是使用 Kafka Operator 管理 Kafka Pod、持久卷、滚动更新和 Topic。实现阶段选择一个支持目标 Kafka 版本的 Operator，并锁定版本，避免自动升级造成环境变化。

第一版 Kubernetes 学习配置：

```text
Kafka 节点数             3
每个节点持久卷           1
booking-events 分区数    3
Topic 副本数             3
最少同步副本数           2
```

还需要配置：

- Pod anti-affinity：尽量不要把三个 Kafka Pod 放到同一个 Kubernetes Node。
- PodDisruptionBudget：维护时至少保留两个 Kafka Pod。
- 独立 PersistentVolumeClaim：Kafka Pod 重启后保留消息。
- 内部 Service：只允许 Worker 和运维任务访问 Kafka。
- readiness 检查：未加入集群的 Kafka Pod 不接收应用流量。

如果 Kubernetes 集群本身只有一个 Node，这套配置仍然只能用于学习，不能抵抗宿主机故障。

### 9.6 AI Service Deployment

- 第一版副本数为 1，监听内部端口 `8090`。
- 使用独立的 ClusterIP Service，只有 Spring Boot API 可以调用。
- 不配置公网 Ingress。
- readiness 检查确认 Python HTTP 服务可以接收请求；不要求检查外部 LLM 永远可用。
- liveness 检查只判断 Python 进程是否卡死。
- LLM API Key 从 Kubernetes Secret 注入，不能写入镜像、ConfigMap 或日志。
- Python 服务不保存本地会话；重启后由 Spring Boot 从 PostgreSQL 提供需要的上下文。
- API 调用 Python 时设置连接和读取超时，AI 故障不能长期占住 API 请求线程。

### 9.7 配置和密码

ConfigMap 保存普通配置：

- 数据库地址
- Kafka 地址
- Redis 地址
- Topic 和 partition 配置
- SSE 心跳时间
- Worker 批量处理数量
- Python AI Service 内部地址
- LLM provider 和模型名称

Secret 保存敏感配置：

- 数据库密码
- JWT `SECRET_KEY`
- LLM API Key
- Spring Boot 与 Python AI Service 的服务间凭证

所有 API 实例必须使用同一个 JWT 密钥。API、Worker 和 Seeder 使用同一套数据库连接信息。

Python AI Service 使用 ClusterIP Service，只允许集群内部访问，不在 Ingress 中配置公开路径。它不直接连接数据库；需要业务数据时，通过带服务凭证的 Spring Boot 内部接口查询。

---

## 10. 测试计划

### 10.1 Profile 测试

分别启动三个 Profile 并检查：

| 检查项 | API | Worker | Seeder |
| --- | --- | --- | --- |
| HTTP Controller | 有 | 无 | 无 |
| SSE Redis 订阅 | 有 | 无 | 无 |
| Kafka consumer | 无 | 有 | 无 |
| Outbox relay | 无 | 有 | 无 |
| 生命周期任务 | 无 | 有 | 无 |
| 数据播种 | 无 | 无 | 有 |
| 执行后退出 | 否 | 否 | 是 |

### 10.2 双实例测试

至少覆盖：

1. 请求轮流进入两个 API，登录身份和返回结果正确。
2. 两个 API 不保存本地热门缓存。
3. 浏览器连接 API A，Worker B 处理消息，SSE 仍能送达。
4. API A 在 SSE 期间退出，客户端连接 API B 后恢复最新状态。
5. 两个 Worker 不会同时领取同一条 Outbox。
6. Worker 在处理过程中退出，另一 Worker 可以接手超时任务。
7. 两个 Worker 同时执行生命周期任务不会产生版本冲突。
8. Seeder Job 运行两次不会重复插入数据。
9. Python AI Service 重启后不丢失业务数据或会话事实；会话由 Spring Boot 存入 PostgreSQL。
10. Python AI Service 不可用时，普通活动搜索、编辑和预订继续工作。

### 10.3 Kafka 集群测试

至少覆盖：

1. 检查三个 Kafka 节点都已加入同一个集群。
2. 检查两个 Topic 的 partition 和副本分布。
3. 连续创建多笔订单，确认消息分布到不同 partition。
4. 确认两个 Worker 都有实际消费记录。
5. 停止当前 partition leader 所在的 Kafka 节点，确认其他副本接管。
6. 节点停止期间继续创建订单，确认通知仍能产生。
7. 同时停止两个 Kafka 节点，确认 Outbox 继续保留消息且没有提前标记成功。
8. 恢复 Kafka 节点，确认积压消息最终被消费。
9. 检查整个过程没有重复通知或重复统计。

### 10.4 其他故障测试

- Redis 停止：热门接口回源 PostgreSQL，REST 正常，SSE 暂时降级。
- Kafka 停止：Outbox 保留待发送消息，恢复后继续处理。
- Worker 被终止：已领取但未完成的任务可以恢复。
- API 被终止：业务数据不丢失，SSE 客户端自动重连。
- Seeder 中途失败：事务回滚，Job 返回失败，重试可以重新执行。
- Python AI Service 或外部 LLM 停止：AI 接口快速返回明确错误，其他业务接口不受影响。

### 10.5 自动化命令

计划为 Makefile 增加：

```text
make up-infra           只启动 PostgreSQL、Redis、Kafka
make seed               运行 Seeder 并等待退出
make up-runtime         启动 API、Worker 和前端
make up-distributed     启动两个 API 和两个 Worker
make test-distributed   执行双实例和故障测试
make kafka-status       查看节点、Topic、partition 和副本状态
make kafka-failover     自动停止一个 Kafka 节点并验证恢复
make test-ai            运行 Python AI 服务测试
```

---

## 11. 实施顺序

### 里程碑一：角色拆分基础

1. 新增 api、worker、seeder Profile。
2. 将 Kafka consumer 和定时任务限制在 worker Profile。
3. 将 Seeder 限制在 seeder Profile，并在完成后退出。
4. 增加 Profile 加载测试。

完成标志：同一镜像可以按三种角色正确启动。

### 里程碑二：移除业务级 JVM 状态

1. 删除 `popularCache`。
2. Redis 失败时直接读取 PostgreSQL。
3. 将 `cacheFallbacks` 改为监控指标。
4. 修复异步请求的 ThreadLocal 清理。

完成标志：API 重启或请求切换实例不会影响业务结果。

### 里程碑三：SSE 完整链路

1. 增加 SSE 权限校验。
2. 实现本机连接注册表。
3. 实现 Redis 提醒发布和订阅。
4. Worker 在事务成功后发送提醒。
5. 前端建立连接、处理提醒、自动重连并刷新 REST 数据。
6. 增加双 API SSE 测试。

完成标志：消息由任意 Worker 处理，都能通知连接在任意 API 上的用户。

### 里程碑四：Worker 多实例安全

1. 增加 Outbox 领取字段和数据库 migration。
2. 实现领取、续期、完成和超时接手。
3. 将生命周期更新改为数据库条件更新。
4. 调整 Kafka partition 和稳定业务 key。
5. 增加双 Worker 与强制终止测试。

完成标志：两个 Worker 可以同时运行，不重复执行同一项工作。

### 里程碑五：Kafka 三节点集群

1. 将 Docker Compose Kafka 改成 3 节点 KRaft 集群。
2. 修改 Topic partition、副本和最少同步副本配置。
3. 修改 Worker 的 Kafka bootstrap 地址。
4. 增加单节点停止和恢复测试。

完成标志：停止任意一个 Kafka 节点后，消息仍能继续发送和消费。

### 里程碑六：部署配置

1. 重写 Docker Compose 角色结构。
2. 修改 frontend/gateway 的 API 转发。
3. 新增 Kubernetes API Deployment、Worker Deployment、Seeder Job、Python AI Service Deployment 和 Kafka 集群配置。
4. 增加健康检查、优雅停机和 SSE Ingress 配置。
5. 更新 README 和发布操作说明。

完成标志：本地 Compose 和 Kubernetes 都使用同一个镜像运行三种角色。

---

## 12. 预计修改文件

主要修改：

```text
backend/src/main/java/dev/kaiwen/eventpulse/common/BaseContext.java
backend/src/main/java/dev/kaiwen/eventpulse/interceptor/JwtInterceptor.java
backend/src/main/java/dev/kaiwen/eventpulse/service/PlatformService.java
backend/src/main/java/dev/kaiwen/eventpulse/kafka/BookingConsumer.java
backend/src/main/java/dev/kaiwen/eventpulse/outbox/KafkaTopicConfig.java
backend/src/main/java/dev/kaiwen/eventpulse/outbox/OutboxRelay.java
backend/src/main/java/dev/kaiwen/eventpulse/outbox/OutboxStatusService.java
backend/src/main/java/dev/kaiwen/eventpulse/repository/OutboxRepository.java
backend/src/main/java/dev/kaiwen/eventpulse/entity/OutboxEvent.java
backend/src/main/java/dev/kaiwen/eventpulse/seed/DemoDataSeeder.java
backend/src/main/resources/application.yml
docker-compose.yml
deploy/frontend/nginx.conf
README.md
Makefile
```

计划新增：

```text
backend/src/main/java/dev/kaiwen/eventpulse/sse/SseConnectionRegistry.java
backend/src/main/java/dev/kaiwen/eventpulse/sse/SseEventSubscriber.java
backend/src/main/java/dev/kaiwen/eventpulse/sse/SseNotificationService.java
backend/src/main/java/dev/kaiwen/eventpulse/worker/EventLifecycleWorker.java
backend/src/main/java/dev/kaiwen/eventpulse/seed/SeederService.java
backend/src/main/resources/application-api.yml
backend/src/main/resources/application-worker.yml
backend/src/main/resources/application-seeder.yml
backend/src/main/resources/db/migration/V1__init.sql
deploy/k8s/*.yml
deploy/k8s/kafka/*.yml
ai-service/Dockerfile
ai-service/app/**
ai-service/tests/**
ai-service/pyproject.toml
ai-service/uv.lock
```

---

## 13. 最终完成标准

只有同时满足以下条件，本次改造才算完成：

1. API、Worker、Seeder 使用同一个镜像和不同 Profile 运行。
2. API 中不再保存热门活动和业务统计等可恢复业务状态。
3. SSE 的最终业务结果保存在 PostgreSQL，Redis 只负责跨实例提醒。
4. SSE 断线、API 重启或 Redis 短暂故障不会造成业务数据丢失。
5. API 扩容不会额外启动 Kafka consumer、Outbox relay 或生命周期任务。
6. 多个 Worker 不会同时处理同一条 Outbox 消息。
7. 活动生命周期任务可以被多个 Worker 安全执行。
8. Seeder 只由 Kubernetes Job 执行，完成后正常退出，失败时返回失败状态。
9. Seeder 可以安全重试，不会生成重复数据。
10. Docker Compose 可以运行多个 API 实例。
11. Kubernetes 配置包含 API Deployment、Worker Deployment、Seeder Job、Service 和 Ingress。
12. 双实例测试和故障测试全部通过。
13. Kafka 使用 3 个节点，业务 Topic 和 DLT 都有 3 个 partition 和 3 个副本。
14. 停止任意一个 Kafka 节点后，系统仍能继续发送和消费消息。
15. 同时停止两个 Kafka 节点时，未发送消息保留在 Outbox，恢复后可以继续处理。
16. Python AI Service 使用独立镜像和内部 Service 部署，不直接暴露到公网。
17. Python AI Service 不保存业务状态，不可用时不影响非 AI 功能。
