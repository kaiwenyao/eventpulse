# 架构与流程图

## 1. 系统架构

```mermaid
flowchart TB
    subgraph client["React Web · User · Organiser · Admin"]
        FE[React 19 + Vite SPA]
    end
    subgraph app["Spring Boot 4 模块化单体（单部署单元；代码按技术分层：controller/service/service.impl/dto/exception/config + 基础设施包，模块业务职责与表访问边界不变）"]
        AUTH[Auth/User]
        CAT[Catalogue/Venue]
        INV[Inventory/Quota]
        BKG[Booking/Ticketing]
        PAY[Payment Orchestrator]
        REC[Recommendation]
        NTF[Notification/Analytics]
        RELAY[Outbox Relay]
        EXP[Expiry Scheduler]
    end
    subgraph db["PostgreSQL 18 · PostGIS · pgvector（数据库是事实源）"]
        PG[(Inventory · Quota · Booking · Payment Balance\nUser Wallets · Commands · Transactional Outbox)]
    end
    KAFKA[(Kafka 4 KRaft)]
    REDIS[(Redis：限流/票券 reveal 缓存，不保存库存事实)]

    FE -->|REST + SSE（同源 /api）| app
    BKG --> PAY
    PAY --> PG
    RELAY --> KAFKA --> NTF
    app --> PG
    app --> REDIS
```

- **资源舱壁**：交易写连接池（hikari tx-pool）与搜索/推荐查询分离超时与并发限制；推荐查询失败降级热门榜单。
- **支付在事务内**：扣款/退款借记/贷记 `user_wallets`，与订单确认/取消同一 PostgreSQL 事务；Kafka 发布仍走 outbox/relay。
- **代码分层组织**：代码目录按技术分层（`controller` 只做请求校验/当前用户/调用 Service 接口/组装响应；`service` 为业务接口；`service.impl` 为 `@Service` 业务实现并承载从 Controller 抽离的业务 SQL；`dto` 承载请求/响应/行 record；`exception`/`config`/`common`/`batch`/`outbox`/`payment` 为基础设施）。这只是代码目录与依赖方式的调整：数据库事务边界、上述领域模块的业务职责以及"不跨模块随意写表"等关键约束全部保持不变；认证过滤器、批处理、outbox 等基础设施 SQL 仍由其所属组件维护。

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
    USERS ||--|| USER_WALLETS : "available_amount_minor >= 0"
    BOOKINGS ||--o{ TICKETS : "booking+sequence 唯一，token_hash 唯一"
    PAYMENT_INTENTS ||--o{ REFUNDS : "command/provider 引用唯一"
    AGGREGATE_COUNTERS ||--o{ OUTBOX : "聚合+序号唯一（无间隙）"
    CONSUMER_CURSORS }o--|| OUTBOX : "consumer+aggregate 唯一"
```

## 3. Booking 履约状态机

```mermaid
stateDiagram-v2
    [*] --> PAYMENT_PENDING : POST /bookings（协议A + 幂等）
    PAYMENT_PENDING --> CONFIRMED : 钱包扣款成功（同一事务出票）
    PAYMENT_PENDING --> EXPIRED : DB now >= expiresAt（SKIP LOCKED claim）
    PAYMENT_PENDING --> CANCELLED_BEFORE_PAYMENT : 取消（释放库存/限额）
    CONFIRMED --> CANCELLED : 取消（撤票、预占、贷记钱包）
    EXPIRED --> [*]
    CANCELLED --> [*]
    note right of EXPIRED
        过期不扣钱包；pay 与 expire 只有一方从 PAYMENT_PENDING 迁出
    end note
```

退款状态（booking.refund_state）：NONE → REFUNDED（取消确认订单时同一事务完成）；
人工 abandon 仍可处理预占残留。

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

## 5. 钱包支付时序

```mermaid
sequenceDiagram
    participant C as Client
    participant API
    participant DB

    C->>API: POST /bookings/{id}/pay
    API->>DB: 协议B锁 booking → quota → inventory → payment_balance → user_wallet
    alt 余额充足
        API->>DB: available -= amt；intent SUCCEEDED；出票；reserved→sold
        API-->>C: 200 已确认
    else 余额不足
        API-->>C: 409 INSUFFICIENT_BALANCE
        Note over DB: 订单仍 PAYMENT_PENDING，到期释放库存
    end
```

取消确认订单时同一事务：撤销票券 → 预占 → 钱包贷记 → reserved→refunded → `CANCELLED`。

命令重试上限后进入 MANUAL_REVIEW 仍适用于非支付 command（NOTIFY 等）；人工重试复用原 providerKey（admin endpoint + 审计）。

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
