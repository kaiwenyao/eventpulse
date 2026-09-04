import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, useParams } from 'react-router-dom'
import { api, formatMoney, formatTime } from '../api'
import { streamBookingEvents, INITIAL_BACKOFF_MS } from '../lib/sse'
import { BookingVo, TicketVo } from '../types'
import { BookingStatusBadge, EmptyState, EventStatusBadge, TicketStatusBadge } from '../ui/Badges'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'
import { resolveApiError } from '../lib/apiError'

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
  const [cancelling, setCancelling] = useState(false)

  useEffect(() => {
    if (!id) return
    const bookingId = Number(id)
    // 先用 REST 取最新状态，再订阅 SSE；提醒到达后重新走 REST 刷新。
    // 提醒是 Redis 广播、不留底：断线（或初始 load 与建连之间的空隙）期间
    // 发生的变化不会再有提醒，因此每次建连成功（含重连）都主动拉一次 REST 补偿。
    const load = () => {
      api<BookingVo>('GET', `/api/bookings/${bookingId}`)
        .then((data) => {
          setBooking(data)
          setError('')
        })
        .catch((e) => setError(resolveApiError(e, 'bookings.missing').message))
      api<TicketVo[]>('GET', `/api/bookings/${bookingId}/tickets`)
        .then((data) => setTickets(Array.isArray(data) ? data : []))
        .catch(() => setTickets([]))
    }
    load()
    const controller = new AbortController()
    void streamBookingEvents(bookingId, load, controller.signal, INITIAL_BACKOFF_MS, load)
    return () => controller.abort()
  }, [id, t])

  async function cancel(current: BookingVo) {
    setCancelling(true)
    try {
      const updated = await api<BookingVo>('POST', `/api/bookings/${current.id}/cancel`)
      setBooking(updated)
      notify(t('bookings.cancelled'), 'success')
    } catch (e) {
      notify({ ...resolveApiError(e, 'bookings.cancelFailed'), tone: 'error' })
    } finally {
      setCancelling(false)
    }
  }

  if (error) return <EmptyState title={error} hint={t('bookings.missingHint')} />
  if (!booking) return <SkeletonCard />

  const related = booking.relatedBookings?.filter((other) => other.id !== booking.id) ?? []

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
          {booking.cancellable ? (
            <button className="btn-secondary" disabled={cancelling} onClick={() => cancel(booking)}>
              {cancelling ? t('common.processing') : t('bookings.cancelOrder')}
            </button>
          ) : booking.status === 'CONFIRMED' && booking.cancelBlockReason ? (
            <span className="muted small">{t(`bookings.blockReason.${booking.cancelBlockReason}`)}</span>
          ) : null}
        </div>
      </header>

      <dl className="fact-grid card">
        <div>
          <dt>{t('bookings.qty')}</dt>
          <dd>{t('bookings.qtyValue', { count: booking.quantity })}</dd>
        </div>
        <div>
          <dt>{t('bookings.unitPrice')}</dt>
          <dd>
            {typeof booking.unitPriceCents === 'number' ? formatMoney(booking.unitPriceCents) : t('common.unknown')}
          </dd>
        </div>
        <div>
          <dt>{t('bookings.paidAmount')}</dt>
          <dd>{formatMoney(booking.paidCents)}</dd>
        </div>
        {typeof booking.refundCents === 'number' && booking.refundCents > 0 && (
          <div>
            <dt>{t('bookings.refundAmount')}</dt>
            <dd>{formatMoney(booking.refundCents)}</dd>
          </div>
        )}
        <div>
          <dt>{t('bookings.placedAt')}</dt>
          <dd>{formatTime(booking.createdAt)}</dd>
        </div>
        {booking.cancelledAt && (
          <div>
            <dt>{t('bookings.cancelledAt')}</dt>
            <dd>{formatTime(booking.cancelledAt)}</dd>
          </div>
        )}
        {booking.eventStartsAt && (
          <div>
            <dt>{t('bookings.eventTime')}</dt>
            <dd>
              {formatTime(booking.eventStartsAt)}
              {booking.eventStatus ? <EventStatusBadge status={booking.eventStatus} /> : null}
            </dd>
          </div>
        )}
        <div>
          <dt>{t('bookings.orderId')}</dt>
          <dd>#{booking.id}</dd>
        </div>
      </dl>

      {booking.checkoutId && related.length > 0 && (
        <section>
          <h2 className="section-title">{t('bookings.checkoutGroup', { id: booking.checkoutId })}</h2>
          <ul className="stack-list">
            {related.map((other) => (
              <li key={other.id} className="card booking-row">
                <div className="booking-copy">
                  <h3>
                    <NavLink to={`/bookings/${other.id}`}>{other.eventTitle}</NavLink>
                  </h3>
                  <p className="muted small">
                    {t('bookings.qtyLine', { count: other.quantity, time: '' })}
                    {' · '}
                    {formatMoney(other.paidCents)}
                  </p>
                </div>
                <div className="row booking-actions">
                  <BookingStatusBadge status={other.status ?? ''} />
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

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
