# ADR-001 模块化单体与事务边界

状态：已接受（第一周冻结）

## 背景

单人开发、14 周窗口，核心风险在库存/限购/支付正确性而不是水平扩展。

## 决策

- 单 Spring Boot 部署单元，按领域模块（auth / catalogue / inventory / booking /
  payment / ticketing / outbox / recs / admin）隔离代码与表访问；模块间只通过
  服务方法通信，不跨模块写表。
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
仓储抽象层——每个 SQL 集中在对应 service，可读性优先。

## 后果

- 正向：事务边界清晰，测试可以针对 SQL 语义；减少框架魔法。
- 负向：需要手写行映射（已用 record 缓解）；未来拆分微服务时需先重构模块边界。
