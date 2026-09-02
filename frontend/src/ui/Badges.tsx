import { useTranslation } from 'react-i18next'
import { CATEGORIES } from '../types'

const CATEGORY_KEYS = new Set<string>(CATEGORIES.map((c) => c.key))

export function CategoryPill({ category }: { category?: string }) {
  const { t } = useTranslation()
  const known = Boolean(category) && CATEGORY_KEYS.has(category!)
  const label = known ? t(`category.${category}`) : category || t('common.uncategorized')
  return <span className={`pill pill-${known ? category : 'unknown'}`}>{label}</span>
}

/**
 * Status chips render straight from API payloads, which can omit the field on
 * partially-populated rows — hence the `?? ''` guard rather than trusting the
 * declared type.
 */
function statusSlug(status?: string) {
  return (status ?? '').toLowerCase() || 'unknown'
}

export function EventStatusBadge({ status }: { status?: string }) {
  const { t } = useTranslation()
  const label = status ? t(`status.event.${status}`, { defaultValue: status }) : t('common.unknown')
  return <span className={`badge badge-${statusSlug(status)}`}>{label}</span>
}

export function BookingStatusBadge({ status }: { status?: string }) {
  const { t } = useTranslation()
  const label = status ? t(`status.booking.${status}`, { defaultValue: status }) : t('common.unknown')
  return (
    <span className={`badge badge-booking-${statusSlug(status)}`}>
      {label}
    </span>
  )
}

export function TicketStatusBadge({ status }: { status?: string }) {
  const { t } = useTranslation()
  const label = status ? t(`status.ticket.${status}`, { defaultValue: status }) : t('common.unknown')
  return (
    <span className={`badge badge-ticket-${statusSlug(status)}`}>
      {label}
    </span>
  )
}

/** Remaining-stock meter. Turns amber then red as an event sells through. */
export function SoldBar({ sold, capacity }: { sold?: number; capacity?: number }) {
  const { t } = useTranslation()
  const total = Number.isFinite(capacity) ? capacity! : 0
  const taken = Number.isFinite(sold) ? sold! : 0
  const ratio = total > 0 ? Math.min(taken / total, 1) : 0
  const level = ratio >= 0.85 ? 'hot' : ratio >= 0.5 ? 'mid' : 'low'
  return (
    <div className="sold">
      <div className={`sold-track ${level}`}>
        <span className="sold-fill" style={{ width: `${ratio * 100}%` }} />
      </div>
      <span className="sold-label">{t('common.remainingTickets', { count: Math.max(total - taken, 0) })}</span>
    </div>
  )
}

export function EmptyState({ title, hint, action }: { title: string; hint: string; action?: React.ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-dot" aria-hidden />
      <p className="empty-title">{title}</p>
      <p className="muted">{hint}</p>
      {action && <div className="empty-action">{action}</div>}
    </div>
  )
}

export function ErrorNote({ message }: { message: string }) {
  if (!message) return null
  return (
    <p className="error-text" role="alert">
      {message}
    </p>
  )
}
