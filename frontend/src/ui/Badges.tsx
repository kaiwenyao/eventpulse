import {
  BOOKING_STATUS_LABELS,
  CATEGORY_LABELS,
  EVENT_STATUS_LABELS,
  TICKET_STATUS_LABELS,
} from '../types'

export function CategoryPill({ category }: { category?: string }) {
  const known = Boolean(category) && category! in CATEGORY_LABELS
  const label = known ? CATEGORY_LABELS[category!] : category || '未分类'
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
  return <span className={`badge badge-${statusSlug(status)}`}>{EVENT_STATUS_LABELS[status ?? ''] ?? status ?? '未知'}</span>
}

export function BookingStatusBadge({ status }: { status?: string }) {
  return (
    <span className={`badge badge-booking-${statusSlug(status)}`}>
      {BOOKING_STATUS_LABELS[status ?? ''] ?? status ?? '未知'}
    </span>
  )
}

export function TicketStatusBadge({ status }: { status?: string }) {
  return (
    <span className={`badge badge-ticket-${statusSlug(status)}`}>
      {TICKET_STATUS_LABELS[status ?? ''] ?? status ?? '未知'}
    </span>
  )
}

/** Remaining-stock meter. Turns amber then red as an event sells through. */
export function SoldBar({ sold, capacity }: { sold?: number; capacity?: number }) {
  const total = Number.isFinite(capacity) ? capacity! : 0
  const taken = Number.isFinite(sold) ? sold! : 0
  const ratio = total > 0 ? Math.min(taken / total, 1) : 0
  const level = ratio >= 0.85 ? 'hot' : ratio >= 0.5 ? 'mid' : 'low'
  return (
    <div className="sold">
      <div className={`sold-track ${level}`}>
        <span className="sold-fill" style={{ width: `${ratio * 100}%` }} />
      </div>
      <span className="sold-label">{Math.max(total - taken, 0)} 张余票</span>
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
