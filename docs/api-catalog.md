# API 目录（v1）

统一约定：
- 错误响应：`{ code, message, fieldErrors, traceId, timestamp }`；对象不存在与无权访问统一 404（隐藏策略）。
- 幂等键：写操作要求 `Idempotency-Key`，>= 128 位熵（32 字符）；服务端只存 HMAC-SHA-256 digest，
  唯一范围为 actor + scope + keyDigest。
- 认证：短期 opaque access token（`Authorization: Bearer`，15 分钟）+ HttpOnly SameSite=Lax
  refresh cookie（30 天，旋转 + 重用检测）。
- 角色/owner 一律来自服务端；DTO 白名单，客户端不能指定 role/status/owner/金额/结果。

| Endpoint | 方法 | 角色 | 关键要求 |
| --- | --- | --- | --- |
| `/api/v1/auth/register` | POST | 公开 | DTO 白名单；强制 USER；限流；防枚举（登录失败统一错误与耗时） |
| `/api/v1/auth/login` | POST | 公开 | Argon2id；限流 |
| `/api/v1/auth/refresh` | POST | cookie/body | 旋转 + family 重用检测；检测到重用吊销全部会话 |
| `/api/v1/auth/logout` | POST | 登录 | family 置 ROTATED |
| `/api/v1/auth/me` · `/auth/me/preferences` | GET/POST | 登录 | 偏好独立记录；位置降精度存储 |
| `/api/v1/events` | GET | 公开 | 签名 keyset cursor（filter hash + queryAsOf 服务端生成 + 过期）；稳定排序元组；只返回可见字段 |
| `/api/v1/events/nearby` | GET | 公开 | lat/lng/radius（上限 50km）；PostGIS ST_DWithin |
| `/api/v1/events/{id}` | GET | 公开 | 可见性过滤；ETag/304；结算时重验 |
| `/api/v1/me/saved-events/{eventId}` | PUT/DELETE | 登录 | 所有权；幂等；产生持久互动事实 |
| `/api/v1/recommendations` | GET | 公开（登录增强） | requestId、冻结候选 cursor（15 分钟）、model/featureVersion、reasonCodes |
| `/api/v1/interactions` | POST | 公开（登录增强） | 批量上限 50；服务端去重（唯一索引）；限流；接收时间为事实 |
| `/api/v1/bookings` | POST | 登录 | 幂等先解析；canonical hash；协议 A；返回 DB 时钟 expiresAt |
| `/api/v1/bookings` / `/{id}` | GET | 登录 | 逐对象所有权；合并履约 + 财务状态 |
| `/api/v1/bookings/{id}/pay` | POST | 登录 | scoped 幂等；单活动 intent；只创建 CAPTURE command |
| `/api/v1/bookings/{id}/cancel` | POST | 登录 | 快照政策；协议 B；退款额度预占；可追踪状态 |
| `/api/v1/bookings/{id}/tickets/reveal` | POST | owner | 授权响应内展示原始 token，可重复读取；AES-GCM 暂存 + TTL，仅服务器保留 pepper 哈希；不进 URL/日志/Kafka |
| `/api/v1/bookings/{id}/events` | GET (SSE) | owner | 最小 SSE：Origin allowlist + 所有权 404 策略 + 心跳；断线后经 REST 同步事实 |
| `/api/v1/organiser/tickets/redeem` | POST | ORGANISER | owner 授权；原子单次使用（ACTIVE→USED）；重复扫码返回原结果；USED/REVOKED 不可枚举错误；限流 |
| `/api/v1/organiser/events` | POST | ORGANISER | 草稿 + 票档 + 库存同事务创建 |
| `/api/v1/organiser/events/{id}/publish` · `/cancel` | POST | ORGANISER | owner 校验；取消先停售、再按游标分批取消订单（幂等可重跑） |
| `/api/v1/organiser/tiers/{id}/inventory` | PATCH | ORGANISER | If-Match 版本；容量下限 reserved+sold+withheld；审计 |
| `/api/v1/organiser/funnel` | GET | ORGANISER | 最小漏斗：浏览/收藏/订单/成交/出票 |
| `/api/v1/admin/reauth` | POST | ADMIN | 新鲜 MFA（默认 10 分钟，可配置）；弹窗确认不算重认证 |
| `/api/v1/admin/exceptions` | GET | ADMIN + 新鲜 MFA | MANUAL_REVIEW / UNKNOWN / 退款失败 / gap / outbox 健康度；分页 + 脱敏 |
| `/api/v1/admin/commands/{id}/retry` | POST | ADMIN + 新鲜 MFA | 复用原 providerKey；审计 |
| `/api/v1/admin/consumer-gaps/{id}/resolve` | POST | ADMIN + 新鲜 MFA | REPLAY / REBUILD_CURSOR / SKIP（需双人批准）；dry-run；审计 |
| `/api/v1/admin/outbox/replay` | POST | ADMIN + 新鲜 MFA | 幂等重放（消费者去重）；dry-run |
| `/api/v1/admin/refunds/{id}/abandon` | POST | ADMIN + 新鲜 MFA | 仅 FAILED/MANUAL_REVIEW；释放预占 + 审计 |
| `/actuator/health` · `/metrics` · `/prometheus` | GET | 公开/内部 | liveness/readiness probes |

OpenAPI UI：`http://localhost:8080/swagger-ui/index.html`（springdoc）。

## 主要错误码

`VALIDATION_FAILED` / `IDEMPOTENCY_KEY_REUSED` / `INSUFFICIENT_INVENTORY` /
`PER_USER_LIMIT_EXCEEDED` / `SALE_WINDOW_CLOSED` / `BOOKING_NOT_PAYABLE` /
`BOOKING_NOT_CANCELLABLE` / `TICKET_NOT_REDEEMABLE` / `AGE_REQUIREMENT_NOT_CONFIRMED` /
`CURSOR_INVALID` / `CURSOR_EXPIRED` / `REAUTH_REQUIRED` / `RATE_LIMITED`
