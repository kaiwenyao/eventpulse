import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
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
  const { t } = useTranslation()
  const cells = 17
  const bits: boolean[] = []
  let h = 0
  for (let i = 0; i < code.length; i++) h = (h * 33 + code.charCodeAt(i)) >>> 0
  for (let i = 0; i < cells * cells; i++) {
    h = (h * 1664525 + 1013904223) >>> 0
    bits.push(h % 3 === 0)
  }
  return (
    <svg className="qr" viewBox={`0 0 ${cells} ${cells}`} role="img" aria-label={t('bookings.ticketCode', { code })}>
      {bits.map((on, i) =>
        on ? <rect key={i} x={i % cells} y={Math.floor(i / cells)} width="1" height="1" fill="currentColor" /> : null,
      )}
    </svg>
  )
}

export function BookingDetailPage() {
  const { t } = useTranslation()
  const { id } = useParams()
  const { notify } = useToast()
  const [booking, setBooking] = useState<BookingVo | null>(null)
  const [tickets, setTickets] = useState<TicketVo[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    if (!id) return
    api<BookingVo>('GET', `/api/bookings/${id}`)
      .then(setBooking)
      .catch(() => setError(t('bookings.missing')))
    api<TicketVo[]>('GET', `/api/bookings/${id}/tickets`)
      .then((data) => setTickets(Array.isArray(data) ? data : []))
      .catch(() => setTickets([]))
  }, [id, t])

  async function cancel(current: BookingVo) {
    try {
      const updated = await api<BookingVo>('POST', `/api/bookings/${current.id}/cancel`)
      setBooking(updated)
      notify(t('bookings.cancelled'), 'success')
    } catch (e) {
      notify(e instanceof ApiError ? e.message : t('bookings.cancelFailed'), 'error')
    }
  }

  if (error) return <EmptyState title={error} hint={t('bookings.missingHint')} />
  if (!booking) return <SkeletonCard />

  return (
    <div className="page">
      <nav className="crumbs">
        <NavLink to="/bookings">{t('bookings.title')}</NavLink>
        <span aria-hidden>/</span>
        <span>{t('bookings.orderNum', { id: booking.id })}</span>
      </nav>

      <header className="page-head">
        <div>
          <h1>{t('bookings.detailTitle')}</h1>
          <p className="muted">{booking.eventTitle}</p>
        </div>
        <div className="row">
          <BookingStatusBadge status={booking.status} />
          {booking.status === 'CONFIRMED' && (
            <button className="btn-secondary" onClick={() => cancel(booking)}>
              {t('bookings.cancelOrder')}
            </button>
          )}
        </div>
      </header>

      <dl className="fact-grid card">
        <div>
          <dt>{t('bookings.qty')}</dt>
          <dd>{t('bookings.qtyValue', { count: booking.quantity })}</dd>
        </div>
        <div>
          <dt>{t('bookings.placedAt')}</dt>
          <dd>{formatTime(booking.createdAt)}</dd>
        </div>
        <div>
          <dt>{t('bookings.orderId')}</dt>
          <dd>#{booking.id}</dd>
        </div>
      </dl>

      <h2 className="section-title">{t('bookings.eTickets')}</h2>
      {tickets.length === 0 ? (
        <EmptyState title={t('bookings.noTickets')} hint={t('bookings.noTicketsHint')} />
      ) : (
        <div className="ticket-wallet">
          {tickets.map((ticket) => (
            <div key={ticket.id} className="card ticket-pass">
              {ticket.code && <TicketQr code={ticket.code} />}
              <div className="ticket-pass-meta">
                <p className="ticket-pass-id">{t('bookings.ticketId', { id: ticket.id })}</p>
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
