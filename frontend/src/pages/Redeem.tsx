import { useState } from 'react'
import { api, formatTime, newIdempotencyKey, ApiError } from '../api'

interface RedeemResult {
  result: string
  ticketId: string
  bookingId: string
  eventId: string
  eventTitle: string
  sequence: number
  usedAt: string
}

export default function Redeem() {
  const [token, setToken] = useState('')
  const [result, setResult] = useState<RedeemResult | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function redeem(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    setResult(null)
    try {
      const data = await api<RedeemResult>('POST', '/api/v1/organiser/tickets/redeem',
        { token: token.trim() }, { idempotencyKey: newIdempotencyKey() })
      setResult(data)
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '网络错误')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="card" style={{ maxWidth: 560, margin: '0 auto' }}>
      <h2>票券核销</h2>
      <p className="muted">
        仅限活动所属主办方；请求限流并要求幂等键。重复扫描同一票券会返回既有结果；
        USED / REVOKED 票券返回不可枚举的业务错误。
      </p>
      <form onSubmit={redeem}>
        <label>票券 token（扫码或手输）</label>
        <input value={token} onChange={(e) => setToken(e.target.value)} required />
        <button type="submit" disabled={busy}>{busy ? '核销中…' : '核销'}</button>
      </form>
      {result && (
        <div className="card" style={{ marginTop: 14 }}>
          <h3><span className="badge success">核销成功</span></h3>
          <p>{result.eventTitle} · 票 #{result.sequence}</p>
          <p className="muted">订单 {result.bookingId.slice(0, 8)}… · 核销时间 {formatTime(result.usedAt)}</p>
        </div>
      )}
      {error && <p className="error-text">{error}</p>}
    </div>
  )
}
