import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import QRCode from 'qrcode'
import { api, formatMoney, formatTime, newIdempotencyKey, ApiError, BOOKING_STATUS_LABEL } from '../api'

interface BookingView {
  id: string
  eventId: string
  tierName: string
  quantity: number
  status: string
  entitlementStatus: string
  refundState: string
  totalMinor: number | null
  currency: string
  policySnapshot: Record<string, unknown>
  expiresAt: string | null
  activeIntent: { id: string; state: string } | null
  refunds: Array<{ id: string; amountMinor: number; state: string }>
  tickets: Array<{ id: string; sequence: number; status: string; usedAt: string | null }>
}

function TicketQr({ token }: { token: string | null }) {
  const [dataUrl, setDataUrl] = useState<string | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    setDataUrl(null)
    setError('')
    if (token) {
      QRCode.toDataURL(token, { width: 220, margin: 1 })
        .then((url) => { if (!cancelled) setDataUrl(url) })
        .catch(() => { if (!cancelled) setError('二维码生成失败') })
    }
    return () => { cancelled = true }
  }, [token])

  if (dataUrl) {
    return (
      <div className="qr-box">
        <img src={dataUrl} alt="票券二维码" width={220} height={220}
          referrerPolicy="no-referrer" crossOrigin="anonymous" />
        <div className="ticket-token">{token}</div>
      </div>
    )
  }
  if (error) return <p className="error-text">{error}</p>
  return <p className="muted" style={{ fontSize: 12 }}>二维码生成中…</p>
}

/**
 * Reveals the staging tokens once and maps each token to its ticket by
 * sequence (the server stores them in issue order 1..N, so the i-th token
 * belongs to ticket sequence i+1). Reveals are repeatable authorized reads —
 * after a refresh or a later re-open every remaining active ticket still
 * gets its QR; nothing is popped and discarded.
 */
function TicketQrPanel({ bookingId, tickets }: {
  bookingId: string
  tickets: Array<{ id: string; sequence: number; status: string }>
}) {
  const [tokens, setTokens] = useState<string[] | null>(null)
  const [error, setError] = useState('')

  async function reveal() {
    setError('')
    try {
      const data = await api<{ tokens: string[] }>('POST',
        `/api/v1/bookings/${bookingId}/tickets/reveal`, {})
      setTokens(data.tokens ?? [])
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '网络错误')
    }
  }

  if (tokens === null) {
    return (
      <div>
        <button className="ghost" onClick={reveal}>显示票券二维码</button>
        {error && <p className="error-text">{error}</p>}
      </div>
    )
  }
  const missing = tickets.some((t) => t.status === 'ACTIVE' && !tokens[t.sequence - 1])
  return (
    <div>
      {tickets.map((t) => (
        <div key={t.id} style={{ marginBottom: 10 }}>
          <p className="muted" style={{ fontSize: 12 }}>#{t.sequence} 的入场二维码：</p>
          {t.status === 'ACTIVE'
            ? <TicketQr token={tokens[t.sequence - 1] ?? null} />
            : <p className="muted" style={{ fontSize: 12 }}>
                该票券状态为 {t.status}，二维码不再提供。
              </p>}
        </div>
      ))}
      {missing && (
        <p className="muted" style={{ fontSize: 12 }}>
          部分票券原文暂存已过期，请保存本次显示的二维码或联系主办方重新处理。
        </p>
      )}
      <p className="muted" style={{ fontSize: 12 }}>
        原文仅在授权响应中返回，可随时重新显示；服务器只保留哈希，不写入日志或事件。
      </p>
      <button className="secondary" onClick={() => setTokens(null)}>隐藏二维码</button>
    </div>
  )
}

export default function BookingDetail() {
  const { id } = useParams()
  const [error, setError] = useState('')
  const [cancelling, setCancelling] = useState(false)

  const booking = useQuery({
    queryKey: ['booking', id],
    queryFn: () => api<BookingView>('GET', `/api/v1/bookings/${id}`),
    refetchInterval: 3000,
  })

  async function cancel() {
    if (!confirm('确认取消该订单？退款将按购买时政策快照处理。')) return
    setCancelling(true)
    setError('')
    try {
      await api('POST', `/api/v1/bookings/${id}/cancel`, { reason: 'user cancel' },
        { idempotencyKey: newIdempotencyKey() })
      await booking.refetch()
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '网络错误')
    } finally {
      setCancelling(false)
    }
  }

  if (!booking.data) return <p className="muted">加载中…</p>
  const b = booking.data
  const cancellable = ['PAYMENT_PENDING', 'CONFIRMED'].includes(b.status)

  return (
    <>
      <div className="card">
        <div className="row spread">
          <h2 style={{ margin: 0 }}>订单 {b.id.slice(0, 8)}…</h2>
          <span className="badge accent">{BOOKING_STATUS_LABEL[b.status] ?? b.status}</span>
        </div>
        <p>
          {b.tierName} × {b.quantity} · <span className="big-price">{formatMoney(b.totalMinor, b.currency)}</span>
        </p>
        <p className="muted">
          权益：{b.entitlementStatus} · 退款：{b.refundState} · 有效期至：{formatTime(b.expiresAt)}
        </p>
        <p>
          <Link to={`/events/${b.eventId}`}>查看活动 →</Link>
        </p>
        {cancellable && (
          <button className="danger" onClick={cancel} disabled={cancelling}>
            {cancelling ? '处理中…' : '取消订单'}
          </button>
        )}
        {error && <p className="error-text">{error}</p>}
      </div>

      {b.refunds.length > 0 && (
        <div className="card">
          <h3>退款记录</h3>
          <table>
            <thead>
              <tr><th>金额</th><th>状态</th></tr>
            </thead>
            <tbody>
              {b.refunds.map((r) => (
                <tr key={r.id}>
                  <td>{formatMoney(r.amountMinor, b.currency)}</td>
                  <td><span className="badge">{r.state}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="muted" style={{ fontSize: 12 }}>
            退款失败时额度保持预占，不会重复退款；人工处理后可恢复。
          </p>
        </div>
      )}

      {b.tickets.length > 0 && (
        <div className="card">
          <h3>票券</h3>
          {b.tickets.map((t) => (
            <div key={t.id} style={{ marginBottom: 14 }}>
              <p>
                #{t.sequence} <span className="badge">{t.status}</span>
                {t.usedAt ? ` · 核销于 ${formatTime(t.usedAt)}` : ''}
              </p>
            </div>
          ))}
          {b.tickets.some((t) => t.status === 'ACTIVE') && (
            <TicketQrPanel
              bookingId={b.id}
              tickets={b.tickets.map((t) => ({ id: t.id, sequence: t.sequence, status: t.status }))}
            />
          )}
        </div>
      )}
    </>
  )
}
