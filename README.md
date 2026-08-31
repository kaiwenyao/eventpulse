# EventPulse

个性化活动发现与票务平台 —— 按《详细项目规划与实施蓝图》实现的可复现交易闭环 MVP。

React 19 · Spring Boot 4.1.1（模块化单体）· PostgreSQL 18 + PostGIS 3.6 + pgvector ·
Apache Kafka 4.3.1（KRaft）· Redis 8.2.9 · Docker Compose 一键启动。

## 这个项目重点验证什么

按计划的优先级排序：**库存/限购/权限正确性 > 支付/退款可恢复性 > 票券安全 > 事件顺序 > 推荐可评估性 > 完整体验**。

| 能力 | 实现位置 | 自动化验证 |
| --- | --- | --- |
| 无超卖 / 限购（协议 A：quota UPSERT → 锁 quota → 锁 inventory → 条件更新） | `service/BookingService` | `BookingConcurrencyIT`（100 并发 vs 50 容量；首次 quota 行并发） |
| 状态竞争唯一获胜（协议 B：booking → quota → inventory → reservation → tickets/payment 固定锁序） | `service/BookingTransitions` | `BookingLifecycleIT`（支付单飞、超时 vs 迟到成功、取消退款） |
| 请求指纹幂等（HMAC digest + canonical JSON hash，claim 随业务事务回滚） | `service/IdempotencyService` + `common/CanonicalJson` | `BookingConcurrencyIT` / `CanonicalJsonTest` |
| 单活动支付意图（部分唯一索引）+ Durable Command 租约 + UNKNOWN 状态查询 + 迟到 capture 自动补偿退款 | `payment/CommandDispatcher` + `payment/SimulatedPaymentGateway` | `BookingLifecycleIT` |
| 退款额度预占（captured / refund_reserved / refunded 同行 CHECK） | `db/migration/V3` | `BookingLifecycleIT` |
| 票券 CSPRNG token + pepper 哈希 + 原子核销 + 重复扫码幂等 | `service/TicketService` | `TicketRedeemIT` |
| 无间隙 Outbox 序号（aggregate counter 行锁递增，回滚无洞）+ relay + consumer cursor + gap/DLT 恢复 | `outbox/*` | `OutboxKafkaIT` |
| 签名 keyset 搜索 cursor（filter hash + queryAsOf 服务端生成）+ PostGIS 半径搜索 | `service/CatalogueService` | 冒烟脚本 + 手工 |
| 推荐 V0/V1（冻结候选 cursor、reason codes、pgvector 可选）+ 互动事件 | `service/RecommendationService` | 冒烟脚本 |
| 最小 SSE（Origin 校验 + 所有权 + 心跳，REST 兜底） | `controller/BookingSseController` | 手工 curl 验证 |
| 推荐离线评估（时间切分 / NDCG@10 / Recall@10 / coverage / diversity / bootstrap CI，synthetic 标注） | `ml/` | `uv run pytest`（3 项，可复现） |

## 快速开始（Docker Compose，唯一必达路径）

```bash
cp .env.example .env       # 按需修改密钥
make up                    # 构建并启动 postgres/kafka/redis/backend/frontend
open http://localhost:3000 # 前端（/api 反代到 backend）
curl http://localhost:8080/actuator/health   # 后端健康检查
```

demo profile 会自动播种：4 个已发布活动、3 个演示账号：

| 角色 | 邮箱 | 密码 |
| --- | --- | --- |
| 普通用户 | `user@eventpulse.dev` | `User!234567890` |
| 主办方 | `organiser@eventpulse.dev` | `Organiser!234567890` |
| 管理员 | `admin@eventpulse.dev` | `Admin!234567890` |

### 一键冒烟测试

```bash
make smoke   # 注册→搜索→创建预订（幂等重放/冲突）→支付→出票→核销→双扫拒绝→支付前取消
```

### 后端测试（Testcontainers，需要 Docker）

```bash
mvn verify   # 单元 + 集成：并发无超卖、限购、幂等、支付单飞、迟到 capture 补偿、
             # 退款预占、票券双扫、Outbox 无间隙/回滚无洞、消费者 gap、DLT
make test    # 同一 `mvn` 命令并自动清理 Testcontainers
```

支付网关场景（演示用，服务端配置）：`.env` 中 `GATEWAY_SCENARIO_RULES`
形如 `pi-late:LATE_SUCCESS:3;pi-fail:FAILURE:0`（按 provider key 前缀匹配）。
prod profile 检测到非空场景规则或默认密钥会拒绝启动。

## 技术基线（详见 docs/adr/ADR-005）

| 层 | 版本 |
| --- | --- |
| Java / Spring Boot | 21 / 4.1.1（第一周兼容性 spike 通过后冻结） |
| 前端 | React 19.2.7 + TypeScript 5.9 + Vite 8.1 |
| 数据 | PostgreSQL 18.6 / PostGIS 3.6.2 / pgvector 0.8.6（源码编译进镜像） |
| 消息 / 缓存 | Kafka 4.3.1（KRaft 单节点，demo 用）/ Redis 8.2.9 |
| 测试 | JUnit 5、Testcontainers（PostGIS + Kafka）；ml 评估用 uv + pytest |
| 构建 | Maven 3.9+（`mvn`）、npm/uv lockfile、CycloneDX SBOM + provenance attest |
| CI | GitHub Actions（`.github/workflows/ci.yml`）：backend 全量测试 / frontend 构建 / ml 评估 |

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

本仓库交付的是 demo 级闭环：单节点 Kafka/Redis 无故障容错；模拟支付网关为进程内隔离实现；
Kubernetes/Helm 等不属于 MVP。进入真实生产前需补齐备份/恢复演练、RPO/RTO、密钥轮换、
真实支付/身份供应商合规等（见计划 §12 与 docs/security-matrix.md）。
