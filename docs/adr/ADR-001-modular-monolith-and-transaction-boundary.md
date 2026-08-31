# ADR-001 模块化单体与事务边界

状态：已接受（第一周冻结）

## 背景

单人开发、14 周窗口，核心风险在库存/限购/支付正确性而不是水平扩展。

## 决策

- 单 Spring Boot 部署单元；模块间只通过服务方法通信，不跨模块写表。代码目录采用技术分层
  （controller / service / service.impl / dto / exception / config / common，以及 batch、outbox、
  payment 等基础设施包）：controller 只负责请求校验、获取当前用户与组装响应，业务接口在 service，
  业务实现以及从 Controller 抽离的业务 SQL 在 service.impl；认证、批处理、outbox、payment 等
  基础设施 SQL 仍由对应组件维护。这只改变代码的组织目录与依赖方式，领域模块的业务职责与下方的
  事务边界约束保持原状（2026-08 目录结构重构，见文末注记）。
- **Booking、Reservation、Inventory、Quota、Tickets、Payment Balance、Idempotency、
  Outbox 的写入都在同一个 PostgreSQL 事务边界内**；任何跨边界的写操作都必须拆分为
  可重试的 durable command。
- 外部调用（支付网关、Kafka 发布）一律不在业务事务内执行：先落 command/outbox，
  dispatcher/relay 在事务外执行，结果以新事务落库。
- 数据库是事实源：Redis、Kafka、SSE、推荐分数都只是提示，提交时以数据库条件更新为准。

## 数据访问

使用 Spring JDBC（JdbcClient/JdbcTemplate）而非 JPA/Hibernate：本项目的不变量
依赖精确 SQL（`FOR UPDATE`、部分唯一索引、条件 UPDATE、`RETURNING`、同一行 CHECK），
JDBC 消除脏检查/缓存的意外行为，记录结构用 Java record 显式映射。代价是没有
仓储抽象层——业务 SQL 集中在对应 service 实现（`service.impl`），基础设施 SQL 保留在其所属
组件中，可读性优先。

## 后果

- 正向：事务边界清晰，测试可以针对 SQL 语义；减少框架魔法。
- 负向：需要手写行映射（已用 record 缓解）；未来拆分微服务时需先重构模块边界。

## 注记（2026-08 目录结构重构）

原有按领域模块（auth / catalogue / booking / ticketing / recs / admin 等）划分的包结构
已重构为技术分层目录：`controller`、`service`（业务接口）、`service.impl`（@Service 实现，
构造器注入）、`dto`、`exception`、`config`、`common`，以及保持独立职责的基础设施包
（`batch` 调度与批处理、`outbox`、`payment`、`seed`、`security`）。

- 这只是代码目录与依赖方式的调整：REST 契约、Spring Security 行为、事务边界、协议 A/B 锁序、
  幂等与补偿流程、outbox/Kafka 行为均未改变。
- 领域模块的业务职责依旧保留（一个 service 实现只写自己模块拥有的表），"不跨模块随意写表"、
  "Booking/Inventory/Quota/Tickets/Payment Balance/Idempotency/Outbox 同一事务边界"等约束不变；
  跨模块协作仍只通过 service 接口。
- Controller 不直接注入 JdbcTemplate/TransactionTemplate，也不依赖实现类；
  原 Controller 内的数据库查询与业务编排已移动到对应 ServiceImpl
  （如 AdminService、SavedEventService、BookingService.payBooking/cancelBooking、
  RecommendationService.recordInteractions）。
