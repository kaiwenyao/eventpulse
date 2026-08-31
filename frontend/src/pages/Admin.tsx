import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api'

interface Exceptions {
  manualReviewCommands: Array<Record<string, unknown>>
  unknownCommands: Array<Record<string, unknown>>
  failedRefunds: Array<Record<string, unknown>>
  unknownPayments: Array<Record<string, unknown>>
  openConsumerGaps: Array<Record<string, unknown>>
  outboxOldestPendingSeconds: number
  commandsRunningLeases: Array<Record<string, unknown>>
}

function asText(value: unknown): string {
  if (value == null) return '—'
  return String(value)
}

export default function Admin() {
  const queryClient = useQueryClient()
  const [password, setPassword] = useState('')
  const [reauthToken, setReauthToken] = useState<string | null>(null)
  const [error, setError] = useState('')

  const exceptions = useQuery({
    queryKey: ['admin', 'exceptions', reauthToken],
    enabled: reauthToken != null,
    queryFn: () => api<Exceptions>('GET', '/api/v1/admin/exceptions', undefined, {
      reauthToken: reauthToken!,
    }),
    retry: false,
  })

  async function reauth() {
    setError('')
    try {
      const data = await api<{ reauthToken: string }>('POST', '/api/v1/admin/reauth', { password })
      setReauthToken(data.reauthToken)
      setPassword('')
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '网络错误')
    }
  }

  async function retryCommand(id: unknown) {
    setError('')
    try {
      await api('POST', `/api/v1/admin/commands/${id}/retry`, { reason: 'admin retry from console' },
        { reauthToken: reauthToken! })
      queryClient.invalidateQueries({ queryKey: ['admin'] })
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '网络错误')
    }
  }

  async function resolveGap(id: unknown, strategy: 'REPLAY' | 'REBUILD_CURSOR' | 'SKIP', dryRun: boolean) {
    setError('')
    try {
      const result = await api<Record<string, unknown>>(
        'POST', `/api/v1/admin/consumer-gaps/${id}/resolve`,
        { strategy, note: `resolved via console (${strategy})`, approvedBy: strategy === 'SKIP' ? 'admin@eventpulse.dev' : undefined, dryRun },
        { reauthToken: reauthToken! })
      window.alert(JSON.stringify(result, null, 2))
      queryClient.invalidateQueries({ queryKey: ['admin'] })
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '网络错误')
    }
  }

  return (
    <>
      <div className="card">
        <h2>管理 · 异常视图</h2>
        <p className="muted">
          高风险操作需要 ADMIN 角色 + 10 分钟内的新鲜重认证（MFA freshness）；
          弹窗确认不能替代重新认证。恢复操作支持 dry-run、幂等重放并写入审计。
        </p>
        {reauthToken ? (
          <p className="ok-text">重认证有效（10 分钟窗口）。</p>
        ) : (
          <div className="row">
            <input type="password" placeholder="再次输入管理员密码" value={password}
              onChange={(e) => setPassword(e.target.value)} style={{ maxWidth: 280 }} />
            <button onClick={reauth}>重新认证</button>
          </div>
        )}
        {error && <p className="error-text">{error}</p>}
      </div>

      {exceptions.data && (
        <>
          <div className="card">
            <h3>人工处理队列（MANUAL_REVIEW）</h3>
            <table>
              <thead>
                <tr><th>命令</th><th>类型</th><th>尝试</th><th>错误</th><th></th></tr>
              </thead>
              <tbody>
                {exceptions.data.manualReviewCommands.map((c) => (
                  <tr key={asText(c['id'])}>
                    <td>{asText(c['id']).slice(0, 8)}…</td>
                    <td>{asText(c['kind'])}</td>
                    <td>{asText(c['attempts'])}</td>
                    <td className="muted">{asText(c['last_error'])}</td>
                    <td><button className="secondary" onClick={() => retryCommand(c['id'])}>重试</button></td>
                  </tr>
                ))}
                {exceptions.data.manualReviewCommands.length === 0 && (
                  <tr><td colSpan={5} className="muted">队列为空。</td></tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="card">
            <h3>UNKNOWN 命令（状态查询中）</h3>
            <table>
              <thead>
                <tr><th>命令</th><th>类型</th><th>下次尝试</th></tr>
              </thead>
              <tbody>
                {exceptions.data.unknownCommands.map((c) => (
                  <tr key={asText(c['id'])}>
                    <td>{asText(c['id']).slice(0, 8)}…</td>
                    <td>{asText(c['kind'])}</td>
                    <td>{asText(c['next_attempt_at'])}</td>
                  </tr>
                ))}
                {exceptions.data.unknownCommands.length === 0 && (
                  <tr><td colSpan={3} className="muted">无。</td></tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="card">
            <h3>退款失败（额度保持预占）</h3>
            <table>
              <thead>
                <tr><th>退款</th><th>订单</th><th>状态</th></tr>
              </thead>
              <tbody>
                {exceptions.data.failedRefunds.map((r) => (
                  <tr key={asText(r['id'])}>
                    <td>{asText(r['id']).slice(0, 8)}…</td>
                    <td>{asText(r['booking_id'])}</td>
                    <td>{asText(r['state'])}</td>
                  </tr>
                ))}
                {exceptions.data.failedRefunds.length === 0 && (
                  <tr><td colSpan={3} className="muted">无。</td></tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="card">
            <h3>消费者 Gap（阻塞该聚合，非全局）</h3>
            <table>
              <thead>
                <tr><th>聚合</th><th>期望</th><th>收到</th><th>恢复操作</th></tr>
              </thead>
              <tbody>
                {exceptions.data.openConsumerGaps.map((g) => (
                  <tr key={asText(g['id'])}>
                    <td>{asText(g['aggregate_id']).slice(0, 8)}…</td>
                    <td>{asText(g['expected'])}</td>
                    <td>{asText(g['received'])}</td>
                    <td className="row">
                      <button className="secondary"
                        onClick={() => resolveGap(g['id'], 'REPLAY', true)}>dry-run</button>
                      <button className="secondary"
                        onClick={() => resolveGap(g['id'], 'REPLAY', false)}>重放</button>
                      <button className="secondary"
                        onClick={() => resolveGap(g['id'], 'REBUILD_CURSOR', false)}>重建游标</button>
                    </td>
                  </tr>
                ))}
                {exceptions.data.openConsumerGaps.length === 0 && (
                  <tr><td colSpan={4} className="muted">无。</td></tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="card">
            <h3>Outbox 健康度</h3>
            <p>
              最老 pending 事件：<strong>{exceptions.data.outboxOldestPendingSeconds?.toFixed(0) ?? 0}s</strong>
              {' '}（持续增长说明 relay 停滞，需查看 Kafka 与 runbook）
            </p>
          </div>
        </>
      )}
    </>
  )
}
