# 架构与流程图

## 1. 系统架构

```mermaid
flowchart TB
    subgraph client["React Web · User · Organiser · Admin"]
        FE[React 19 + Vite SPA]
    end
    subgraph app["Spring Boot 4 模块化单体（单部署单元，按领域模块隔离代码与表访问）"]
        AUTH[Auth/User]
        CAT[Catalogue/Venue]
        INV[Inventory/Quota]
        BKG[Booking/Ticketing]
        PAY[Payment Orchestrator]
        REC[Recommendation]
        NTF[Notification/Analytics]
        RELAY[Outbox Relay]
        DISP[Command Dispatcher]
        EXP[Expiry Scheduler]
    end
    subgraph db["PostgreSQL 18 · PostGIS · pgvector（数据库是事实源）"]
        PG[(Inventory · Quota · Booking · Payment Balance\nCommands · Transactional Outbox)]
    end
    KAFKA[(Kafka 4 KRaft)]
    REDIS[(Redis：限流/票券 reveal 缓存，不保存库存事实)]
    GW[隔离的模拟支付网关\n（场景仅由服务端配置决定）]

    FE -->|REST + SSE（同源 /api）| app
    BKG --> PAY
    PAY --> DISP --> GW
    RELAY --> KAFKA --> NTF
    app --> PG
    app --> REDIS
```

- **资源舱壁**：交易写连接池（hikari tx-pool）与搜索/推荐查询分离超时与并发限制；推荐查询失败降级热门榜单。
- **外部调用异步**：支付/退款/通知先落 durable command + outbox，dispatcher 在事务外调用，结果以新事务落库并写 outbox。

## 2. 简化 ER（含不变量）

```mermaid
erDiagram
    USERS ||--o{ BOOKINGS : places
    EVENTS ||--o{ TICKET_TIERS : has
    TICKET_TIERS ||--|| INVENTORY : "1:1（available+reserved+sold+withheld = capacity）"
    TICKET_TIERS ||--o{ USER_TIER_QUOTA : "active + confirmed <= per_user_limit"
    BOOKINGS ||--|| RESERVATIONS : "唯一预留"
    BOOKINGS ||--o{ PAYMENT_INTENTS : "部分唯一索引保证单个活动 intent"
    BOOKINGS ||--|| PAYMENT_BALANCE : "captured / refund_reserved / refunded 同行，预占+已退 <= 已收"
    BOOKINGS ||--o{ TICKETS : "booking+sequence 唯一，token_hash 唯一"
    PAYMENT_INTENTS ||--o{ REFUNDS : "command/provider 引用唯一"
    AGGREGATE_COUNTERS ||--o{ OUTBOX : "聚合+序号唯一（无间隙）"
    CONSUMER_CURSORS }o--|| OUTBOX : "consumer+aggregate 唯一"
```

## 3. Booking 履约状态机

```mermaid
stateDiagram-v2
    [*] --> PAYMENT_PENDING : POST /bookings（协议A + 幂等）
    PAYMENT_PENDING --> CONFIRMED : capture 成功（dispatcher，出票）
    PAYMENT_PENDING --> PAYMENT_FAILED : capture 失败（释放库存/限额）
    PAYMENT_PENDING --> EXPIRED : DB now >= expiresAt（SKIP LOCKED claim）
    PAYMENT_PENDING --> CANCELLED_BEFORE_PAYMENT : 取消（释放库存/限额，取消 READY capture）
    CONFIRMED --> CANCELLATION_PENDING : 取消（先撤票，预占退款）
    CANCELLATION_PENDING --> CANCELLED : refund 成功且预占清零
    EXPIRED --> [*]
    CANCELLED --> [*]
    note right of EXPIRED
        迟到/额外 capture 成功时订单不复活：
        自动创建补偿 REFUND command
    end note
```

退款状态（booking.refund_state）：NONE → PENDING → REFUNDED / REFUND_FAILED / MANUAL_REVIEW；
退款失败时预占保留，人工"放弃"才释放并审计。

## 4. 创建预订（协议 A）时序

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API
    participant I as Idempotency
    participant DB as PostgreSQL

    C->>A: POST /bookings (Idempotency-Key: >=128bit)
    A->>I: HMAC(key)->keyDigest; canonical(body)->requestHash
    A->>DB: INSERT idempotency (同事务 claim)
    alt 冲突
        DB-->>A: 等首事务提交后读取
        A-->>C: 409（hash 不同）/ 重放响应 / 202 Retry-After
    end
    A->>DB: UPSERT quota; SELECT quota FOR UPDATE
    A->>DB: SELECT inventory FOR UPDATE
    A->>DB: UPDATE inventory SET available-=q,reserved+=q WHERE available>=q
    A->>DB: UPDATE quota SET active+=q WHERE active+q+confirmed<=limit
    A->>DB: INSERT booking(PAYMENT_PENDING, expiresAt=DB now+ttl) + reservation + outbox
    A->>DB: idempotency -> COMPLETED(201,响应)
    A-->>C: 201 {bookingId, expiresAt, snapshot}
```

任何一步失败：整个事务回滚（包括幂等 claim 与 counter），无库存残留。

## 5. 支付 / 迟到 capture 补偿时序

```mermaid
sequenceDiagram
    participant C as Client
    participant API
    participant D as Dispatcher(租约)
    participant G as 模拟网关
    participant DB

    C->>API: POST /bookings/{id}/pay
    API->>DB: 协议B锁 booking；部分唯一索引保证单活动 intent
    API->>DB: INSERT intent(providerKey) + CAPTURE command + outbox
    D->>DB: FOR UPDATE SKIP LOCKED claim（RUNNING + lease 30s）
    D->>G: capture(providerKey)（事务外）
    G-->>D: SUCCESS / FAILURE / UNKNOWN
    alt SUCCESS 且 booking 仍 PAYMENT_PENDING
        D->>DB: 协议B：确认订单、出票、inventory reserved->sold、quota active->confirmed、outbox
    else SUCCESS 但订单已终止（迟到 capture）
        D->>DB: 余额行 captured+=amt；创建 REFUND command（key=rf-late-<captureKey>）
    else FAILURE 且 booking 仍待支付
        D->>DB: 订单 PAYMENT_FAILED；库存/限额释放
    else UNKNOWN
        D->>DB: state=UNKNOWN_QUERY，状态查询循环，不猜测结果
    end
```

命令重试上限后进入 MANUAL_REVIEW；人工重试复用原 providerKey（admin endpoint + 审计）。

## 6. 有序 Outbox 与消费者

```mermaid
flowchart LR
    TX[业务事务] -->|锁 aggregate counter 递增| OC[(outbox + counter)]
    OC -->|relay：单 worker，按 aggregateSequence 顺序| K[Kafka topic, key=aggregateId]
    K --> CONS[NotificationConsumer]
    CONS -->|同事务| CC[(consumer cursor)] & NB[(notifications)]
    CONS -->|seq > last+1| GAP[(consumer_gaps：只阻塞该聚合)]
    K -. poison .-> DLT[topic.DLT]
```

- 发布成功后标记前宕机 → 重复发布（设计内），消费者按 cursor 去重。
- offset 提交在 DB commit 之后，kill 后重放安全。
- Gap 恢复三选一（全部审计）：修复后重放 / 从可信快照重建游标 / 双人批准跳过。
