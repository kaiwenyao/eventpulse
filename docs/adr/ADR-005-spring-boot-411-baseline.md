# ADR-005 技术基线：Spring Boot 4.1.1 兼容性 spike 结论

状态：已接受（第一周兼容性 spike 完成，唯一基线冻结，不双轨）

## Spike 范围

按计划 §16 第 1 周要求，在冻结基线前验证以下内容在 Spring Boot 4.1.1 / Java 21
下的真实表现（本地 Maven Central 解析 + 编译 + 上下文启动）：

| 关注点 | 结论 |
| --- | --- |
| Boot 4.1.1 / Spring Framework 7.0.9 / Security 7.1.1 | 可用；`requestMatchers` 必须用 `HttpMethod` 重载 |
| Jackson | Boot 4 默认 **Jackson 3**（`tools.jackson.databind`），注解仍是 `com.fasterxml.jackson.annotation`；`WRITE_DATES_AS_TIMESTAMPS` 等旧特性已移除（默认 ISO 日期） |
| 数据访问 | 采用 Spring JDBC（见 ADR-001）；无 Hibernate 依赖 |
| Flyway 12.4 | 需要 `flyway-database-postgresql`；PostGIS 扩展迁移正常 |
| Kafka | Boot 4 不再从裸 `spring-kafka` 自动配置，需显式 `@EnableKafka` + 自定义 Producer/Consumer/ContainerFactory（含 DLT errorHandler） |
| Testcontainers 2.0.5 | 模块改名 `testcontainers-*`；类包路径迁移（`org.testcontainers.postgresql/kafka`）；`@ServiceConnection` 支持 Postgres 容器，Kafka 容器用 `@DynamicPropertySource` |
| springdoc 3.1.0 | 与 Boot 4 兼容（同时带入 Jackson 2 供其内部使用，与应用 Jackson 3 共存无冲突） |
| 密码哈希 | `Argon2PasswordEncoder` + BouncyCastle 1.81 |
| 证书/镜像 | postgis/postgis:18-3.6、apache/kafka:4.3.1、redis:8.2.9-alpine、temurin 21、node 24、nginx 1.27 manifest 验证通过 |

## 决策

- 冻结基线：**Java 21 + Spring Boot 4.1.1 + Jackson 3 + Spring JDBC + Flyway 12 +
  spring-kafka 4.1.1 + Testcontainers 2.0.5**。
- 不引入 Boot 3.5 双轨；后续升级以新 ADR 记录。
- 版本细节以 lockfile/镜像 digest 固定（backend pom、frontend package-lock、
  compose image tag + build digest）。

## 后果

- Jackson 3 的 API 差异（包名、unchecked JacksonException、默认行为）是本项目
  主要迁移坑，已在代码与注释中标注。
- 显式 Kafka 装配带来少量样板代码，换来对 container factory/DLT 行为的完全控制。
