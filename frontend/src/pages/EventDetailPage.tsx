import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, useNavigate, useParams } from 'react-router-dom'
import { api, formatMoney, formatTime } from '../api'
import { useAuth } from '../auth'
import { relativeTime } from '../lib/datetime'
import { EventVo } from '../types'
import { CategoryPill, EmptyState, ErrorNote, EventStatusBadge, SoldBar } from '../ui/Badges'
import { ClockIcon, HeartIcon, PinIcon } from '../ui/Icons'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'
import { resolveApiError } from '../lib/apiError'

function BookingPanel({ event, onError }: { event: EventVo; onError: (msg: string) => void }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { notify } = useToast()
  const maxQty = Math.max(1, Math.min(event.maxQuantityPerBooking || 10, event.remaining || 1))
  const [qty, setQty] = useState(1)
  const [busy, setBusy] = useState(false)
  const [addingToCart, setAddingToCart] = useState(false)

  async function confirm() {
    setBusy(true)
    try {
      const booking = await api<{ id: number }>('POST', '/api/bookings', { eventId: event.id, quantity: qty })
      notify(t('detail.booked'), 'success')
      navigate(`/bookings/${booking.id}`)
    } catch (e) {
      const { message, action } = resolveApiError(e, 'detail.bookFailed')
      onError(message)
      notify({ message, action, tone: 'error' })
    } finally {
      setBusy(false)
    }
  }

  // 加购不扣余额、不占库存；合并数量规则由后端校验。
  async function addToCart() {
    setAddingToCart(true)
    try {
      await api('POST', '/api/cart/items', { eventId: event.id, quantity: qty })
      notify(t('detail.addedToCart'), 'success')
    } catch (e) {
      const { message, action } = resolveApiError(e, 'detail.addToCartFailed')
      onError(message)
      notify({ message, action, tone: 'error' })
    } finally {
      setAddingToCart(false)
    }
  }

  return (
    <div className="book-box">
      <div className="field">
        <div className="field-head">
          <label htmlFor="book-qty">{t('detail.qty')}</label>
          <span className="field-count">{t('detail.maxQty', { count: maxQty })}</span>
        </div>
        <div className="stepper">
          <button type="button" className="btn-secondary btn-icon" aria-label={t('detail.minus')} onClick={() => setQty((n) => Math.max(1, n - 1))}>
            −
          </button>
          <input
            id="book-qty"
            type="number"
            min={1}
            max={maxQty}
            value={qty}
            onChange={(e) => setQty(Math.min(Math.max(1, Number(e.target.value) || 1), maxQty))}
          />
          <button type="button" className="btn-secondary btn-icon" aria-label={t('detail.plus')} onClick={() => setQty((n) => Math.min(maxQty, n + 1))}>
            +
          </button>
        </div>
      </div>
      <p className="book-total">
        <span className="muted">{t('detail.total')}</span>
        <strong>{event.priceCents === 0 ? t('common.free') : formatMoney(event.priceCents * qty)}</strong>
      </p>
      <button className="btn-primary btn-block" disabled={busy} onClick={confirm}>
        {busy ? t('detail.submitting') : t('detail.confirm')}
      </button>
      <button className="btn-secondary btn-block" disabled={addingToCart} onClick={addToCart}>
        {addingToCart ? t('detail.addingToCart') : t('detail.addToCart')}
      </button>
    </div>
  )
}

export function EventDetailPage() {
  const { t } = useTranslation()
  const { id } = useParams()
  const { user } = useAuth()
  const { notify } = useToast()
  const [event, setEvent] = useState<EventVo | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api<EventVo>('GET', `/api/events/${id}`)
      .then(setEvent)
      .catch(() => setError(t('detail.missing')))
  }, [id, t])

  async function toggleFavourite() {
    if (!event) return
    try {
      if (event.favourite) await api('DELETE', `/api/events/${event.id}/favourite`)
      else await api('POST', `/api/events/${event.id}/favourite`)
      setEvent({ ...event, favourite: !event.favourite })
      notify(event.favourite ? t('detail.unfavourited') : t('detail.favourited'), 'success')
    } catch (e) {
      notify({ ...resolveApiError(e, 'common.operationFailed'), tone: 'error' })
    }
  }

  if (error) return <EmptyState title={error} hint={t('detail.missingHint')} />
  if (!event) return <SkeletonCard />

  return (
    <article className="detail">
      <div
        className={`detail-cover${event.coverUrl ? '' : ' detail-cover-empty'}`}
        style={event.coverUrl ? { backgroundImage: `url(${event.coverUrl})` } : undefined}
      >
        <div className="detail-cover-meta">
          <CategoryPill category={event.category} />
          <EventStatusBadge status={event.status} />
        </div>
      </div>

      <div className="detail-body">
        <div className="detail-main">
          <h1>{event.title}</h1>
          {event.summary && <p className="detail-summary">{event.summary}</p>}

          <dl className="detail-facts">
            <div>
              <dt>
                <ClockIcon /> {t('detail.startsAt')}
              </dt>
              <dd>
                {formatTime(event.startsAt)}
                <span className="muted small"> · {relativeTime(event.startsAt)}</span>
              </dd>
            </div>
            <div>
              <dt>
                <PinIcon /> {t('detail.place')}
              </dt>
              <dd>{[event.city, event.venueName, event.address].filter(Boolean).join(' · ')}</dd>
            </div>
          </dl>

          <h2 className="detail-section-title">{t('detail.about')}</h2>
          <p className="detail-desc">{event.description}</p>

          {event.attendanceNotes && (
            <>
              <h2 className="detail-section-title">{t('detail.notes')}</h2>
              <p className="detail-desc">{event.attendanceNotes}</p>
            </>
          )}
          {event.contactInfo && <p className="muted small">{t('detail.contact', { contact: event.contactInfo })}</p>}
          {event.cancellationReason && <p className="error-text">{t('detail.cancelled', { reason: event.cancellationReason })}</p>}
        </div>

        <aside className="detail-side">
          <div className="detail-price">{event.priceCents === 0 ? t('common.free') : formatMoney(event.priceCents)}</div>
          <SoldBar sold={event.sold} capacity={event.capacity} />
          {user ? (
            <BookingPanel event={event} onError={setError} />
          ) : (
            <NavLink to="/login" className="btn-primary btn-block btn-link">
              {t('detail.loginToBook')}
            </NavLink>
          )}
          {user && (
            <button className={`btn-secondary btn-block${event.favourite ? ' is-active' : ''}`} onClick={toggleFavourite}>
              <HeartIcon /> {event.favourite ? t('detail.unsave') : t('detail.save')}
            </button>
          )}
          <ErrorNote message={error} />
        </aside>
      </div>
    </article>
  )
}
