import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { api, ApiError, formatTime } from '../api'
import { BookingVo } from '../types'
import { BookingStatusBadge, EmptyState } from '../ui/Badges'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'

export function BookingsPage() {
  const { t } = useTranslation()
  const [items, setItems] = useState<BookingVo[]>([])
  const [loading, setLoading] = useState(true)
  const { notify } = useToast()

  useEffect(() => {
    api<BookingVo[]>('GET', '/api/bookings')
      .then((data) => setItems(Array.isArray(data) ? data : []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false))
  }, [])

  async function cancel(booking: BookingVo) {
    try {
      const updated = await api<BookingVo>('POST', `/api/bookings/${booking.id}/cancel`)
      setItems((prev) => prev.map((x) => (x.id === booking.id ? updated : x)))
      notify(t('bookings.cancelled'), 'success')
    } catch (e) {
      notify(e instanceof ApiError ? e.message : t('bookings.cancelFailed'), 'error')
    }
  }

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>{t('bookings.title')}</h1>
          <p className="muted">{t('bookings.sub')}</p>
        </div>
      </header>

      {loading ? (
        <SkeletonCard />
      ) : items.length === 0 ? (
        <EmptyState
          title={t('bookings.emptyTitle')}
          hint={t('bookings.emptyHint')}
          action={
            <NavLink to="/" className="btn-primary btn-link">
              {t('bookings.goEvents')}
            </NavLink>
          }
        />
      ) : (
        <ul className="stack-list">
          {items.map((b) => (
            <li key={b.id} className="card booking-row">
              <div className="booking-copy">
                <h3>
                  <NavLink to={`/bookings/${b.id}`}>{b.eventTitle}</NavLink>
                </h3>
                <p className="muted small">{t('bookings.qtyLine', { count: b.quantity, time: formatTime(b.createdAt) })}</p>
              </div>
              <div className="row booking-actions">
                <BookingStatusBadge status={b.status} />
                {b.status === 'CONFIRMED' && (
                  <button className="btn-secondary btn-sm" onClick={() => cancel(b)}>
                    {t('bookings.cancel')}
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
