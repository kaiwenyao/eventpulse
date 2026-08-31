# EventPulse 开发计划（简化版）

Spring Boot 4 · Kafka · PostgreSQL · 初学者友好的 CRUD 练习项目。对齐 firmament：Controller → Service → Repository。已删除 RateLimiter、DbClock、Outbox、钱包、推荐、Redis。

同步原子库存预留；首次限购行安全初始化；操作级锁协议；单活动支付意图；用户钱包扣款/退款；退款金额预占；无间隙聚合序号；可恢复 DLT；原子票券核销；可评估推荐。

面向普通用户、活动主办方与平台管理员的双端平台。工程重点是库存与限购正确性、支付/超时/取消竞争、Kafka 可靠传递、票券核销安全、推荐评估以及端到端可观测性。单人开发，14 周、每周 15–20 小时，并预留约 15% 风险缓冲。

Kaiwen Yao · 2026-08-31（支付章修订：用用户余额替换模拟支付网关）

## 目录

1. 目标、成功标准与边界
2. 产品流程与页面
3. 系统架构与模块边界
4. 技术栈与版本策略
5. 数据模型与数据库不变量
6. 预订、幂等与状态竞争
7. 支付、退款、通知与票券外部边界
8. API 与实时通信
9. Kafka 与有序 Transactional Outbox
10. 搜索与 AI 推荐
11. 前端实现
12. 安全、权限与数据治理
13. 测试与质量策略
14. 可观测性、部署与恢复
15. CI/CD 与供应链
16. 14 周实施路线图
17. 验收、演示与风险
18. 第一周可执行清单与最终原则

---

## 1 目标、成功标准与边界

### 1.1 要解决的问题

通用活动平台常依赖关键词与热门榜单，难以同时处理兴趣、距离、可参加时间、预算和实时余票。EventPulse 用一个可复现闭环展示发现、收藏、预订、付款、出票、核销与取消如何反馈给推荐，并在并发、重复请求、消息重放和进程故障下保持订单、权益、库存与财务状态可解释。

### 1.2 角色与核心价值

| 角色 | 核心任务 | 安全边界 |
| --- | --- | --- |
| 普通用户 | 发现、筛选、收藏、预订、**钱包余额支付**、查看票券、取消 | 只能访问自己的资源；不能指定金额、角色、owner 或支付结果。 |
| 主办方 | 发布活动、配置票档与库存、核销票券、查看最小漏斗 | 只能操作自己的 organiser/event/tier；核销必须属于自己活动。 |
| 管理员 | 审核、封禁、异常处理、DLT 恢复与人工退款 | 最小权限；高风险动作需要新鲜 MFA/重新认证、原因和追加审计。 |
| 推荐系统 | 召回、排序、解释和离线评估 | 不成为交易、库存、权限或年龄资格事实源。 |

### 1.3 可量化成功标准

| 维度 | MVP 门槛 | 证据 |
| --- | --- | --- |
| 交易正确性 | 测试覆盖模型下无超卖、限购突破、重复出票、重复核销、重复扣款和超额退款 | 固定 READ COMMITTED；100 并发；库存、quota、booking、ticket、payment balance、user_wallets 不变量。 |
| 可恢复性 | 支付、超时、取消只有受控结果；余额不足不扣款且订单仍待支付 | 条件更新借记钱包；pay vs expire 单胜者；kill/restart；人工队列可追踪。 |
| 事件可靠性 | 数据库事实不因 relay 故障丢失；重复、乱序和 gap 可检测并恢复 | 无间隙 aggregate sequence、cursor、replay、lag、DLT 和恢复报告。 |
| 推荐效果 | V1 与热门基线有离线对照且没有未来泄漏 | 时间切分、point-in-time 特征、NDCG@10、Recall@10、coverage、diversity 和置信区间。 |
| 性能 | 搜索 p95 < 250ms；创建预订 p95 < 500ms | 固定硬件、数据规模、并发、预热、运行次数与波动。 |
| 安全质量 | 核心权限矩阵、交易状态机和异常分支自动化 | 对象/字段授权、票券核销、CSRF/CORS、依赖/密钥扫描和日志脱敏。 |

可恢复性**不再**要求网关 UNKNOWN / 迟到成功：支付不离开订单事务。

### 1.4 范围与非目标

Must：认证与资源级授权、活动目录、PostGIS 搜索、单票档预订、同步库存/限购、**用户钱包扣款**、超时、确认后取消与退款（同一事务贷记钱包）、票券核销、Outbox/Kafka、推荐 V0/V1、收藏、关键测试和基础监控。

Should：最小主办方漏斗、提醒、推荐解释、多样性重排、管理异常视图、退款状态 UI。

Could：完整退款 UI、候补、降价提醒、完整管理后台、A/B 平台、学习排序、好友兴趣、优惠券、动态定价。

非目标：**真实收单清算**、生产级身份/年龄证明、座位图、多币种税务、完整反欺诈、真实供应商双向同步、Kubernetes 深度演示，以及宣称合成数据代表真实商业效果。本仓库不做充值/提现 API。

---

## 2 产品流程与页面

### 2.1 普通用户主流程

1. 注册普通账号；兴趣类别、粗粒度常驻区域、距离、预算和常用时间进入独立偏好记录。注册 DTO 不接受 role/status/owner。注册时写入 `user_wallets` 并贷记开户赠金。
2. 浏览附近、相似收藏和趋势列表；地图必须有等价列表。搜索 cursor 使用服务端签名，客户端不能指定 queryAsOf。
3. 查看活动、票档、年龄资格要求、取消政策版本、可售提示和推荐理由。未知年龄资格显示“结算时需确认”，不显示“已满足”。
4. 以高熵幂等键创建单票档预订。服务端重新验证销售窗口、限购、库存和价格，返回数据库时钟生成的 expiresAt。
5. 同一 booking 只允许一个活动支付意图。结算页展示应付与钱包余额。支付在订单事务内借记钱包并立即确认出票；余额不足返回 `409 INSUFFICIENT_BALANCE`，订单仍为待支付，到期释放库存。
6. 已确认订单按购买时快照政策申请取消。接受即撤销票券、预占退款额度并贷记钱包，响应即为 `CANCELLED`。
7. 入场时由所属主办方原子核销票券；重复扫码返回既有结果，不重复使用权益。

### 2.2 主办方与管理员流程

主办方创建草稿、配置时间、政策与票档后发布。容量调整不得低于 reserved + sold + withheld。取消活动先在一个事务中停止新预订，再按稳定游标分批取消订单。

管理员审核、封禁、DLT 恢复和人工退款必须记录操作者、原因、前后值、trace ID 与时间。MFA freshness 默认 10 分钟并可配置。

### 2.3 页面与验收重点

| 页面 | 主要组件 | 验收重点 |
| --- | --- | --- |
| Discovery | 推荐分区、筛选、地图/列表 | keyset 稳定契约；曝光去重；理由可验证。 |
| 活动详情 | 时间地点、票档、政策、收藏 | 状态、年龄资格、政策版本明确；结算重验。 |
| 结算 | 价格/政策快照、倒计时、应付与钱包余额、支付 | 刷新可恢复；同请求复用 key；单活动 payment intent；余额不足可见；支付成功立即出票。 |
| 订单票券 | 权益、财务、票券、取消 | 取消后退回钱包、核销状态可见。 |
| 核销页 | 扫码/手输、结果与活动上下文 | owner 授权、限流、token 脱敏、原子单次使用。 |
| 主办方后台 | 活动、票档、库存、漏斗 | 所有权、If-Match、容量下限、审计。 |
| 管理异常视图 | DLT、退款预占残留、gap | 新鲜 MFA、最小权限、脱敏、dry-run。 |

---

## 3 系统架构与模块边界

### 3.1 关键架构决策

- 模块化单体优先：单 Spring Boot 部署单元，按领域模块隔离代码与表访问。
- 交易同步：Booking、Reservation、Inventory、Quota、Tickets、Payment Balance、**User Wallet** 和 Outbox 在同一 PostgreSQL 事务边界。
- **钱包在同一事务边界**：支付借记、取消贷记均不离开该事务。不再隔离模拟网关，也不再为 CAPTURE/VOID/REFUND 插入 Durable Command。
- Kafka 发布仍先落 outbox，由 relay 在事务外执行。
- 数据库是事实源：Redis、Kafka、SSE 和推荐分数不替代交易条件更新。
- 资源舱壁：交易写、搜索/向量查询和后台批任务使用不同连接池、数据库角色、超时与并发限制。

### 3.2 模块职责

| 模块 | 负责 | 不负责 |
| --- | --- | --- |
| Auth/User | 登录、短期 access、旋转 refresh、封禁检查、偏好、资格事实、钱包开户赠金 | 接受客户端角色；订单状态。 |
| Catalogue/Venue | 活动、政策版本、票档元数据、搜索与发布 | 扣减库存；支付结果。 |
| Inventory/Quota | capacity、available/reserved/sold/withheld、限购计数 | 用户身份；支付策略。 |
| Booking/Ticketing | 订单履约、快照、竞争、出票与核销 | 在事务外猜测支付结果。 |
| Payment | payment intent、金额预占、钱包借记/贷记 | 把网络成功等同于本地成功；真实收单。 |
| Recommendation | point-in-time 特征、召回、排序、解释、评估 | 交易事实、权限或年龄资格决策。 |
| Notification / Analytics | durable delivery、幂等发送、漏斗与事件质量 | 反向决定交易状态。 |

---

## 4 技术栈与版本策略

版本基线于 2026-08-30 复核。仓库通过 lockfile、BOM、Wrapper 与镜像 digest 固定实际补丁。

| 层级 | 基线 | 约束 |
| --- | --- | --- |
| 后端 | Java 21；Spring Boot 4.1.1；Maven Wrapper 3.9.x | 只保留一个 Boot 基线。 |
| 前端 | Node 24 LTS；React 19.2.7；TypeScript 5.9；Vite 8.1 | 固定 package manager 与 lockfile。 |
| 数据 | PostgreSQL 18.6；PostGIS 3.6.2；pgvector 0.8.6 | 约束在真实 PostgreSQL 测试。 |
| 消息缓存 | Kafka 4.3.1；Redis Open Source 8.2.9 | Kafka KRaft；Redis 不保存库存事实。 |
| ML | Python 3.13；sentence-transformers 5.x；scikit-learn 1.8.x | uv.lock 固定完整补丁。 |
| 测试交付 | Testcontainers、Playwright、pytest、k6、Docker Compose | K8s/Helm 不属于 MVP。 |

---

## 5 数据模型与数据库不变量

### 5.1 核心实体

在既有 users / events / ticket_tiers / inventory / user_tier_quota / bookings / reservations / payment_intents / payment_balance / refunds / tickets / idempotency / outbox / commands 之外：

| 实体 | 关键字段 | 约束/索引 |
| --- | --- | --- |
| user_wallets | user_id PK, currency, available_amount_minor, version | `available_amount_minor >= 0`；注册与 seed 插入一行。 |
| payment intents | bookingId, attemptNo, state, requestedAmount, providerKey | booking 只有一个活动 intent；providerKey 唯一。 |
| payment balance | captured / refundReserved / refunded | 同行 CHECK：reserved + refunded ≤ captured。 |

**去掉** `gateway_results` 与网关场景配置。`commands` 表保留（NOTIFY / admin 异常视图），预订流程不再插入 CAPTURE/VOID/REFUND。

开户赠金：`eventpulse.wallet.signup-grant-minor` 默认 1_000_000 minor（¥10,000），写在 `application.yml`，不再引入类似 `GATEWAY_SCENARIO_RULES` 的场景环境变量。

### 5.2 库存、限购、权益和金额不变量

- available, reserved, sold, withheld 均非负且总和严格等于 capacity。
- activeQuantity + confirmedQuantity ≤ perUserLimit。
- 支付前失败/超时/取消：reserved 减、available 增、quota active 减。确认：reserved 减、sold 增、reservation CONSUMED、生成 quantity 张票券。
- 接受确认后取消时先撤销未使用票券。任何 USED 票券默认拒绝整单自动取消并转人工。
- refundReservedAmount + refundedAmount ≤ capturedAmount；取消时先预占再贷记钱包，成功时 reserved 转 refunded。

### 5.3 两条无环锁协议

协议 A——创建预订：UPSERT quota → 锁 quota → 锁 inventory → 条件更新 → 插入 booking/reservation/outbox。该流程不会等待一个既有 booking。

协议 B——既有订单迁移：先无锁读取 booking 获取关联 ID；正式锁定 **booking → quota → inventory → reservation → tickets → payment_balance → user_wallet**；随后重新验证 booking status/version。`user_wallet` 必须在 booking 之后，避免与钱包交叉死锁。

容量调整只锁 inventory。批量活动取消每笔订单独立执行协议 B。

### 5.4 时间与事实源

所有交易截止时间使用数据库 UTC 时钟生成和比较。Redis、页面倒计时、SSE 与 Kafka 均为提示，提交时重新验证数据库状态。

---

## 6 预订、幂等与状态竞争

### 6.1 请求指纹幂等

幂等键必须是至少 128 位随机值。服务端保存 HMAC-SHA-256 digest；唯一范围为 actor + endpoint scope + keyDigest。失败事务回滚首次认领。

### 6.2 状态模型

Booking 履约状态与 Payment/Refund 财务状态分离：

- 履约：PAYMENT_PENDING 可迁移至 CONFIRMED、EXPIRED 或 CANCELLED_BEFORE_PAYMENT。
- 确认后取消：CONFIRMED → CANCELLED（同一事务完成退款）。不再要求客户端等待 CANCELLATION_PENDING。
- 退款状态：无需退款、已退款；人工 abandon 仍可处理预占残留。
- 过期不扣钱包；pay 与 expire 只有一方从 PAYMENT_PENDING 迁出。

### 6.3 支付单飞、超时与取消竞争

创建 payment intent 时按协议 B 锁 booking，并通过部分唯一索引保证只有一个活动 intent。改变幂等键不能绕过该约束。

支付、超时调度器和取消请求均先锁同一 booking。只有成功从 PAYMENT_PENDING 条件迁移的一方修改库存、quota 与权益。失败方重读并返回无副作用结果。

超时调度器多副本按 expiresAt, bookingId 使用 FOR UPDATE SKIP LOCKED claim。扫描条件为 PAYMENT_PENDING + DB now ≥ expiresAt。

---

## 7 支付、退款、通知与票券外部边界

### 7.1 钱包扣款（同步确认）

1. `POST /pay` 按协议 B 锁 booking，再锁 quota / inventory / payment_balance / user_wallet。
2. 条件更新 `UPDATE user_wallets SET available = available - ? WHERE user_id = ? AND available >= ?`。0 行 → `INSUFFICIENT_BALANCE`。
3. 扣款成功：intent SUCCEEDED（active = FALSE）、确认订单、出票、inventory reserved→sold、写 outbox。响应即为已确认。
4. 不再为支付路径插入 Durable Command，不再 UNKNOWN 查询，不再迟到 capture 补偿。

`commands` 表可留作 NOTIFY / admin 异常视图。预订流程不写 CAPTURE/VOID/REFUND。

### 7.2 取消与退款原子边界

接受取消的本地事务按协议 B：验证快照政策与票券均未 USED；撤销票券；调整 inventory/quota；在 payment_balance 原子预占；贷记钱包；reserved→refunded；订单 CANCELLED / REFUNDED。无退款时直接转 CANCELLED。

### 7.3 票券生成与原子核销

票券原始 token 使用 CSPRNG 生成至少 128 位熵，只保存带服务端 pepper 的 hash。核销与取消同时发生时都先锁 booking，再锁 ticket，因此只有一个迁移获胜。

---

## 8 API 与实时通信

`POST /api/v1/bookings/{id}/pay`：scoped 幂等；单活动 intent；**事务内扣钱包并确认出票**，不再“只创建 command”。

`POST /api/v1/bookings/{id}/cancel`：快照政策、协议 B、退款额度预占后贷记钱包，响应即为 CANCELLED。

`GET /api/v1/auth/me`：返回 `availableAmountMinor` 与 `currency`。

错误响应包含 code/message/fieldErrors/traceId/timestamp。新增 `INSUFFICIENT_BALANCE`（409）。对象不存在和无权访问采用统一隐藏策略。

---

## 9 Kafka 与有序 Transactional Outbox

Topic、无间隙序号、relay、gap 与 DLT 恢复协议不变。支付相关事件：`payment.intent_created` 与 `booking.confirmed` 在同一业务事务写出；取消写出 `refund.succeeded` 与 `booking.cancelled`。不再发出 `booking.late_capture_compensated`。

---

## 10 搜索与 AI 推荐

搜索分页一致性契约、point-in-time 特征与离线评估不变。

---

## 11 前端实现

结算页展示应付与钱包余额；支付成功直接进入已确认（去掉“等待网关结果”）；`INSUFFICIENT_BALANCE` 显示明确错误。

---

## 12 安全、权限与数据治理

金额、政策、资格与 owner 来自服务端；单支付意图；钱包条件借记；退款预占。公开 demo 与生产声明分离。真实支付/身份供应商合规不在本仓库。

---

## 13 测试与质量策略

| 层 | 工具 | 覆盖 |
| --- | --- | --- |
| 单元 | JUnit/Mockito、Vitest | 状态机、政策快照、金额预占、quota、序号分配、排序公式。 |
| 安全切片 | Spring Security Test、RTL | 对象/字段授权、DTO、CSRF/CORS、MFA freshness、票券 owner。 |
| 数据库集成 | Testcontainers PostgreSQL/PostGIS | CHECK/FK、首次 quota、两条锁协议、幂等 UPSERT、退款 balance、票券核销、钱包借记。 |
| Kafka | Testcontainers Kafka | sequence/gap、重放。 |
| 端到端 | Playwright | 注册、收藏、搜索、钱包支付立即出票、超时、余额不足、取消退回钱包、核销。 |
| 推荐评估 | pytest | point-in-time、时间切分、曝光偏差、基线、可复现性。 |
| 负载故障 | k6 + kill/pause | 无超卖/限购突破/双扣/双退；连接池舱壁；lag 与恢复。 |

并发矩阵至少包含：同票档 100 并发；同用户首次 quota 行并发；同 key 同/不同请求；两个支付 key；两个退款请求；**支付与超时同刻**；支付与取消同刻；容量调整与预订；核销与取消；relay 高低序号竞争；DLT 双重放。不再要求网关成功后 kill 或迟到 capture。

---

## 14 可观测性、部署与恢复

关键指标包括 API rate/errors/p95/p99、交易与查询 pool、数据库 deadlock、outbox oldest pending、aggregate gap、consumer lag、refund reserved age、manual review、票券拒绝率、库存等式、推荐 fallback。Compose 是必达路径。

---

## 15 CI/CD 与供应链

PR：lint/format/type-check、单元、安全、数据库/Kafka 集成、契约和扫描；不注入发布密钥。Flyway expand/contract。

---

## 16 14 周实施路线图

| 周 | 目标 | 交付 | 退出条件 |
| --- | --- | --- | --- |
| 1 | 骨架/兼容 spike | monorepo、Compose、CI、版本 ADR、基础迁移 | 一键启动；只保留一个 Boot 基线。 |
| 2 | 认证/目录 | 注册登录、owner 模型、活动草稿/发布、OpenAPI | 不能自授角色；跨 owner 失败。 |
| 3 | 搜索/收藏 | PostGIS、签名 keyset cursor、详情、收藏 | 新写入不破坏遍历；结算重验。 |
| 4 | 原子预订 | inventory/quota UPSERT、协议 A、快照、幂等 | 无超卖；首次并发无限购突破。 |
| 5 | 状态竞争 | 协议 B、超时/取消前支付、死锁与并发矩阵 | 竞争只有一个履约迁移获胜。 |
| 6 | 有序 Outbox | aggregate counter、relay、consumer cursor、gap/DLT | kill/restart 到达；回滚无序号洞。 |
| 7 | 支付单飞 | payment intent、钱包扣款、成功立即确认 | 双 key 不双扣；余额不足 409。 |
| 8 | 超时竞争 | expire 与 pay 单胜者；过期不扣钱包 | 支付与超时同刻只有一方迁出。 |
| 9 | 取消退款 | payment balance、退款预占、同一事务贷记钱包 | 无超额退款；取消后钱包恢复。 |
| 10 | 票券核销 | token、owner redeem、取消竞争 | 双扫只成功一次；token 不泄漏。 |
| 11 | 推荐 V0/V1 | point-in-time、冻结结果、embedding、离线报告 | 报告可复现且标注 synthetic。 |
| 12 | 反馈/安全 | interactions、最小 SSE、权限矩阵、限流、E2E | 无已知越权；重连恢复。 |
| 13 | 故障/观测 | k6、kill/pause、OTel、Dashboard、runbooks | 从 trace 定位失败订单。 |
| 14 | 缓冲/交付 | benchmark、恢复演练、README、演示与修复 | 新机器可启动；数字有证据。 |

不得裁剪库存/quota 并发、两条锁协议、幂等、支付单飞、退款预占、Outbox sequence、票券授权或对象授权测试。

---

## 17 验收、演示与风险

### 17.1 MVP 发布门槛

- 地理、时间、类别、价格、资格与可售筛选符合声明的分页一致性契约。
- 库存、quota、权益、payment balance、user_wallets 和 aggregate sequence 不变量有数据库与并发测试。
- 支付单飞、余额不足、超时竞争、取消退回钱包和票券双扫自动化。
- README 从空环境启动；benchmark 记录硬件、参数、数据、预热、运行次数和波动。

### 17.2 8 分钟演示

40 秒问题与架构；45 秒搜索/收藏；65 秒预订、钱包支付与出票；65 秒库存与首次限购并发；65 秒双支付 key 与余额不足；60 秒取消并退回钱包；45 秒票券双扫/取消竞争；45 秒 point-in-time 推荐；45 秒 Outbox gap 恢复；25 秒边界总结。

去掉 LATE_SUCCESS / 退款 FAILURE 注入。演示余额不足可把 `user_wallets.available_amount_minor` 置 0。

### 17.3 主要风险

| 风险 | 概率 | 影响 | 应对 |
| --- | --- | --- | --- |
| 范围过大 | 中 | 高 | 14 周 + 15% 缓冲；按检查点裁剪非核心体验。 |
| 锁协议漂移 | 中 | 高 | ADR、代码模板、集成测试、deadlock 指标。 |
| 重复扣款/超额退款 | 中 | 高 | 单 payment intent、钱包条件更新、退款预占、余额行锁。 |
| Outbox 逻辑乱序 | 中 | 高 | 无间隙 counter、lane lease、consumer cursor、gap 恢复。 |
| 票券泄漏/重复核销 | 低 | 高 | 高熵 token hash、owner 授权、原子迁移、限流。 |
| 查询拖垮交易 | 中 | 高 | 独立 pool/role/timeout、候选上限、热门降级。 |
| 推荐未来泄漏 | 中 | 中 | point-in-time 特征、时间切分、数据审计。 |

---

## 18 第一周可执行清单与最终原则

第一周只承诺 15–20 小时内可验证的骨架。四个核心 ADR：模块化单体与事务边界；协议 A/B；无间隙 Outbox；payment intent + 钱包扣款 + refund balance。

最终必须提交：README、ER/状态/时序图、ADR、API/事件目录和安全矩阵；库存、quota、支付、退款、核销与事件顺序报告；Dashboard/告警、余额不足/超时竞争/DLT runbook；seed、benchmark、train、evaluate 与 demo 脚本。

**库存、限购与权限正确性 > 支付/退款可恢复性 > 票券安全 > 事件顺序与可靠性 > 推荐可评估性 > 完整体验 > 额外功能数量**

## 版本与设计依据

Spring Boot、React、Apache Kafka、PostgreSQL 18.6、pgvector、PostGIS 3.6.2、Redis 8.2、OWASP API Security Top 10 官方文档。
