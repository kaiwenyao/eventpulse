# 安全矩阵（demo 级实现边界已标注）

## 认证

| 控制 | 实现 | 验收 |
| --- | --- | --- |
| 密码哈希 | Argon2id（Argon2PasswordEncoder，19MB 内存/2 迭代） | 弱口令注册 400；登录失败统一 message + 耗时（防枚举） |
| 短期 access | opaque 256-bit token，sha256 存储，15 分钟 TTL | 过期后 401 |
| refresh 旋转 | family 结构 + used_at 标记 | 二次使用同 token → 全 family/会话吊销（token_version++） |
| 封禁 | users.status + token_version 检查 | BANNED 用户 access 立即失效 |
| 管理员 MFA freshness | POST /admin/reauth（10 分钟窗口 token，哈希存储） | 无新鲜 reauth 的 admin 调用 → REAUTH_REQUIRED |

## 授权

| 控制 | 实现 | 验收 |
| --- | --- | --- |
| 默认拒绝 | SecurityFilterChain 白名单 + 方法级 @PreAuthorize | 未认证写操作 401 |
| 对象级授权 | booking/ticket/事件 owner 查询带 user_id/organiser_id | 跨用户/跨主办方统一 404 |
| 角色服务端赋值 | 注册 DTO 白名单（无 role/status/owner 字段） | 注册后角色恒为 USER |
| 金额/结果服务端定价 | price/policy 快照，客户端不可指定金额 | 篡改 body 数量→价格按快照计算 |

## 交易安全

| 控制 | 实现 | 验收 |
| --- | --- | --- |
| 库存/限额 | 同行 CHECK + 条件 UPDATE（协议 A/B） | 100 并发无超卖、无限购突破（自动化） |
| 单支付意图 | 部分唯一索引 | 双 key 一个意图（自动化） |
| 钱包扣款 | 条件 UPDATE available >= amt | 余额不足 409；过期不扣钱包（自动化） |
| 退款预占 | balance 单行 CHECK + 预占后贷记钱包 | 无超额退款；取消后 refunded == captured（自动化） |
| 票券 | CSPRNG >=128bit + HMAC(pepper) 哈希；原子 ACTIVE→USED；owner 校验；限流 | 双扫单成功；跨 owner 404；token 不入日志/事件/URL（自动化） |

## 浏览器边界

| 控制 | 实现 | 验收 |
| --- | --- | --- |
| CORS | allowlist（env 配置），allowCredentials | 恶意 Origin 不被放行 |
| CSRF | 无 cookie 会话（Bearer）+ refresh cookie SameSite=Lax 限路径 /api/v1/auth | 跨站无法携带 refresh cookie 发起 POST |
| 票券页面泄漏 | nginx `Referrer-Policy: no-referrer`；应用零第三方资源 | QR 页面无外域请求 |
| 传输 | demo 环境明文，prod 需 TLS 终结（Secure cookie 注释标明） | — |

## 外部边界与隐私

- MVP 不做任意服务端 URL 抓取；活动封面为白名单字符串字段（不 fetch）。
- 位置：偏好仅存粗粒度（city 字符串）；nearby 查询参数不落库；日志不记录完整位置。
- 年龄资格：未知不推断，只显示"结算时需确认"；受控 eligibility fact 表。
- 删除传播：用户删除（cascade）级联 interactions 置空、画像删除；账务行按财务要求保留匿名引用。

## Kafka 数据治理

- payload 白名单 + 分类标签（见 event-catalog.md）；不含 token/完整位置。
- demo 单节点无 ACL/传输加密；生产需 ACL + TLS + 保留期策略（非 MVP，诚实标注）。

## 供应链与审计

- 版本固定：pom exact patch、package-lock、镜像 tag（附 ADR-005 验证记录）；
  CI 应追加 SBOM/签名/依赖与密钥扫描（计划 §15，本仓库提供 make 目标位与文档，不注入发布密钥）。
- audit_log 追加写（无 UPDATE 路径），记录 actor/action/resource/before/after/reason/traceId。
- 日志脱敏：logback pattern 不输出请求体；token/票券原文不进任何日志或 Kafka payload。

## 生产化缺口（诚实声明）

备份/恢复演练与 RPO/RTO、密钥轮换、真实支付/身份供应商合规、Kafka/Redis 集群化、
WAF/限流边缘化、多副本 dispatcher 的 lane 化等不在本仓库交付范围内。
