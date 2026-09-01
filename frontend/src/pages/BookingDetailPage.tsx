import { useEffect, useState } from 'react'
import { NavLink, useParams } from 'react-router-dom'
import { api, ApiError, formatTime } from '../api'
import { BookingVo, TicketVo } from '../types'
import { BookingStatusBadge, EmptyState, TicketStatusBadge } from '../ui/Badges'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'

/**
 * Deterministic pseudo-QR rendered from the ticket code.
 *
 * This is a visual stand-in, not a scannable QR symbol: check-in reads the
 * printed code below it. Same code always draws the same pattern, so a ticket
 * looks stable across reloads.
 */
function TicketQr({ code }: { code: string }) {
  const cells = 17
  const bits: boolean[] = []
  let h = 0
  for (let i = 0; i < code.length; i++) h = (h * 33 + code.charCodeAt(i)) >>> 0
  for (let i = 0; i < cells * cells; i++) {
    h = (h * 1664525 + 1013904223) >>> 0
    bits.push(h % 3 === 0)
  }
  return (
    <svg className="qr" viewBox={`0 0 ${cells} ${cells}`} role="img" aria-label={`票码 ${code}`}>
      {bits.map((on, i) =>
        on ? <rect key={i} x={i % cells} y={Math.floor(i / cells)} width="1" height="1" fill="currentColor" /> : null,
      )}
    </svg>
  )
}

export function BookingDetailPage() {
  const { id } = useParams()
  const { notify } = useToast()
  const [booking, setBooking] = useState<BookingVo | null>(null)
  const [tickets, setTickets] = useState<TicketVo[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    if (!id) return
    api<BookingVo>('GET', `/api/bookings/${id}`)
      .then(setBooking)
      .catch(() => setError('订单不存在'))
    api<TicketVo[]>('GET', `/api/bookings/${id}/tickets`)
      .then((data) => setTickets(Array.isArray(data) ? data : []))
      .catch(() => setTickets([]))
  }, [id])

  async function cancel(current: BookingVo) {
    try {
      const updated = await api<BookingVo>('POST', `/api/bookings/${current.id}/cancel`)
      setBooking(updated)
      notify('订单已取消', 'success')
    } catch (e) {
      notify(e instanceof ApiError ? e.message : '取消失败', 'error')
    }
  }

  if (error) return <EmptyState title={error} hint="这个订单可能不属于你。" />
  if (!booking) return <SkeletonCard />

  return (
    <div className="page">
      <nav className="crumbs">
        <NavLink to="/bookings">我的预订</NavLink>
        <span aria-hidden>/</span>
        <span>订单 #{booking.id}</span>
      </nav>

      <header className="page-head">
        <div>
          <h1>订单详情</h1>
          <p className="muted">{booking.eventTitle}</p>
        </div>
        <div className="row">
          <BookingStatusBadge status={booking.status} />
          {booking.status === 'CONFIRMED' && (
            <button className="btn-secondary" onClick={() => cancel(booking)}>
              取消订单
            </button>
          )}
        </div>
      </header>

      <dl className="fact-grid card">
        <div>
          <dt>票数</dt>
          <dd>{booking.quantity} 张</dd>
        </div>
        <div>
          <dt>下单时间</dt>
          <dd>{formatTime(booking.createdAt)}</dd>
        </div>
        <div>
          <dt>订单号</dt>
          <dd>#{booking.id}</dd>
        </div>
      </dl>

      <h2 className="section-title">电子票</h2>
      {tickets.length === 0 ? (
        <EmptyState title="暂无电子票" hint="订单确认后电子票会自动生成。" />
      ) : (
        <div className="ticket-wallet">
          {tickets.map((ticket) => (
            <div key={ticket.id} className="card ticket-pass">
              {ticket.code && <TicketQr code={ticket.code} />}
              <div className="ticket-pass-meta">
                <p className="ticket-pass-id">票 #{ticket.id}</p>
                <TicketStatusBadge status={ticket.status} />
                {ticket.code && <p className="muted mono">{ticket.code}</p>}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
