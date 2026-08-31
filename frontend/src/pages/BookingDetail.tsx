import { useState } from 'react'
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

function TicketQr({ bookingId }: { bookingId: string }) {
  const [dataUrl, setDataUrl] = useState<string | null>(null)
  const [token, setToken] = useState<string | null>(null)
  const [error, setError] = useState('')

  async function reveal() {
    setError('')
    try {
      const data = await api<{ tokens: string[] }>('POST',
        `/api/v1/bookings/${bookingId}/tickets/reveal`, {})
      const raw = data.tokens[0]
      setToken(raw ?? null)
      if (raw) {
        setDataUrl(await QRCode.toDataURL(raw, { width: 220, margin: 1 }))
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '网络错误')
    }
  }

  if (dataUrl) {
    return (
      <div className="qr-box">
        <img src={dataUrl} alt="票券二维码" width={220} height={220}
          referrerPolicy="no-referrer" crossOrigin="anonymous" />
        <div className="ticket-token">{token}</div>
        <p className="muted" style={{ fontSize: 12 }}>
          原文仅在本次授权响应中展示，请保存到本地；服务器只保留哈希。
        </p>
      </div>
    )
  }
  return (
    <div>
      <button className="ghost" onClick={reveal}>显示票券二维码</button>
      {error && <p className="error-text">{error}</p>}
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
              {t.status === 'ACTIVE' && <TicketQr bookingId={b.id} />}
            </div>
          ))}
        </div>
      )}
    </>
  )
}
