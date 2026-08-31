# 8 分钟演示脚本（对应计划 §17.2）

前提：`make up` 完成栈启动（seed 数据就绪），`BASE=http://localhost:3000`。
演示账号见 README。每个环节标注时长与验证点。

| 时间 | 环节 | 操作 | 验证点 |
| --- | --- | --- | --- |
| 0:00–0:40 | 问题与架构 | 打开 docs/architecture.md | 模块化单体、数据库为事实源、外部调用异步 |
| 0:40–1:25 | 搜索/收藏 | Discovery 页搜索"城市"、切类别/排序；点击收藏 | 签名 keyset cursor（DevTools 看 cursor 参数）；列表与地图等价 |
| 1:25–2:30 | 预订支付出票 | 详情页选票→创建预订→倒计时→支付 | expiresAt 服务端生成；支付按钮绑定唯一意图；确认后订单页出现票券 |
| 2:30–3:35 | 库存与首次限购并发 | `make test` 跑 BookingConcurrencyIT（或 k6 脚本） | 100 并发 50 容量无超卖；首单并发无限购突破（日志/报告） |
| 3:35–4:40 | 双支付 key 与迟到成功 | 同订单两个标签页点支付；后台配 LATE_SUCCESS 规则演示迟到 capture | 两个 key 返回同一意图；迟到成功订单不复活、自动补偿退款 |
| 4:40–5:40 | 取消/退款预占/失败 | 确认订单点取消；后台把退款规则设 FAILURE 再取消一笔 | 进入 CANCELLATION_PENDING 时票券已撤销、额度已预占；失败保留预占、admin 可重试 |
| 5:40–6:25 | 票券双扫/取消竞争 | 核销页扫同一 token 两次；另一浏览器同时取消 | 单次成功；第二次返回不可枚举错误；USED 票拒绝整单取消 |
| 6:25–7:10 | point-in-time 推荐 | /api/v1/recommendations 看 requestId/model/featureVersion/reasonCodes；翻页 cursor | 冻结候选集内翻页；展示时过滤取消/结束活动 |
| 7:10–7:55 | Outbox gap / command kill 恢复 | admin 异常视图：MANUAL_REVIEW 重试、gap REPLAY dry-run、DLT 列表 | 恢复支持 dry-run + 审计；从 trace 定位到 command |
| 7:55–8:20 | 边界总结 | docs/security-matrix.md 末节 | demo ≠ 生产合规；单节点 Kafka/Redis 无容错声明 |

## 演示数据注入（迟到 capture / 退款失败）

`.env`：
```
GATEWAY_SCENARIO_RULES=pi-late:LATE_SUCCESS:5;rf-fail:FAILURE:0
```
重启 backend 后，按 providerKey 前缀注入（演示支付时从响应读取 providerKey，
复制前缀到规则即可）。所有场景只存在于 demo profile。
