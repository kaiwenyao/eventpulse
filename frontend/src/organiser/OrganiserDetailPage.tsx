import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, formatMoney, formatTime } from '../api'
import { EVENT_STATUSES, EventVo } from '../types'
import { CategoryPill, EmptyState, ErrorNote, EventStatusBadge, SoldBar } from '../ui/Badges'
import { ConfirmDialog } from '../ui/Modal'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'

/** Visual order of the happy path; CANCELLED is terminal and sits outside it. */
const LIFECYCLE = EVENT_STATUSES.filter((s) => s.key !== 'CANCELLED')

type PendingAction = 'cancel' | 'delete' | 'archive' | null

function LifecycleStepper({ status }: { status: string }) {
  const { t } = useTranslation()
  const activeIndex = LIFECYCLE.findIndex((s) => s.key === status)
  const cancelled = status === 'CANCELLED'
  return (
    <ol className={`stepper-track${cancelled ? ' is-cancelled' : ''}`} aria-label={t('organiser.lifecycle')}>
      {LIFECYCLE.map((step, index) => {
        const state = cancelled ? 'idle' : index < activeIndex ? 'done' : index === activeIndex ? 'current' : 'idle'
        return (
          <li key={step.key} className={`step step-${state}`} aria-current={state === 'current' ? 'step' : undefined}>
            <span className="step-dot" aria-hidden />
            {t(`status.event.${step.key}`)}
          </li>
        )
      })}
      {cancelled && <li className="step step-cancelled">{t('organiser.cancelledStep')}</li>}
    </ol>
  )
}

export function OrganiserDetailPage() {
  const { t } = useTranslation()
  const { id } = useParams()
  const navigate = useNavigate()
  const { notify } = useToast()
  const [event, setEvent] = useState<EventVo | null>(null)
  const [error, setError] = useState('')
  const [pending, setPending] = useState<PendingAction>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api<EventVo>('GET', `/api/organiser/events/${id}`)
      .then(setEvent)
      .catch((e) => setError(e instanceof ApiError ? e.message : t('common.loadFailed')))
  }, [id, t])

  async function run(action: () => Promise<unknown>, successMessage: string, stay = false) {
    setBusy(true)
    try {
      await action()
      notify(successMessage, 'success')
      if (!stay) navigate('/organiser/events')
    } catch (e) {
      const message = e instanceof ApiError ? e.message : t('common.operationFailed')
      setError(message)
      notify(t('organiser.opFailed', { message }), 'error')
    } finally {
      setBusy(false)
      setPending(null)
    }
  }

  if (error && !event) return <EmptyState title={error} hint={t('organiser.ownOnly')} />
  if (!event) return <SkeletonCard />

  const isDraft = event.status === 'DRAFT'
  const isLive = event.status === 'PUBLISHED' || event.status === 'ONGOING'
  const isClosed = event.status === 'FINISHED' || event.status === 'CANCELLED'

  return (
    <div className="page">
      <nav className="crumbs">
        <NavLink to="/organiser">{t('nav.console')}</NavLink>
        <span aria-hidden>/</span>
        <NavLink to="/organiser/events">{t('organiser.events')}</NavLink>
        <span aria-hidden>/</span>
        <span>{event.title}</span>
      </nav>

      <header className="page-head">
        <div>
          <div className="row detail-top">
            <CategoryPill category={event.category} />
            <EventStatusBadge status={event.status} />
          </div>
          <h1>{event.title}</h1>
          <p className="muted">
            {event.city} · {formatTime(event.startsAt)}
          </p>
        </div>
      </header>

      <LifecycleStepper status={event.status} />

      <ErrorNote message={error} />

      <div className="card fact-card">
        <dl className="fact-grid">
          <div>
            <dt>{t('organiser.price')}</dt>
            <dd>{event.priceCents === 0 ? t('common.free') : formatMoney(event.priceCents)}</dd>
          </div>
          <div>
            <dt>{t('organiser.soldCap')}</dt>
            <dd>
              {event.sold}/{event.capacity}
            </dd>
          </div>
          <div>
            <dt>{t('organiser.venue')}</dt>
            <dd>{event.venueName || t('organiser.unspecified')}</dd>
          </div>
          <div>
            <dt>{t('organiser.version')}</dt>
            <dd>v{event.version ?? 0}</dd>
          </div>
        </dl>
        <SoldBar sold={event.sold} capacity={event.capacity} />
      </div>

      {event.cancellationReason && (
        <div className="callout callout-error">
          <p className="callout-title">{t('organiser.cancelledBanner')}</p>
          <p className="muted">{event.cancellationReason}</p>
        </div>
      )}

      <div className="row action-rail">
        {isDraft && (
          <button
            className="btn-primary"
            disabled={busy}
            onClick={() => run(() => api('POST', `/api/organiser/events/${event.id}/publish`), t('organiser.published'))}
          >
            {t('organiser.publish')}
          </button>
        )}
        <NavLink to={`/organiser/events/${event.id}/edit`} className="btn-secondary btn-link">
          {t('organiser.edit')}
        </NavLink>
        <NavLink to={`/organiser/events/${event.id}/attendees`} className="btn-secondary btn-link">
          {t('organiser.attendees')}
        </NavLink>
        <button
          className="btn-secondary"
          disabled={busy}
          onClick={() => run(() => api('POST', `/api/organiser/events/${event.id}/duplicate`), t('organiser.copied'))}
        >
          {t('organiser.copy')}
        </button>
        {isLive && (
          <button className="btn-danger" disabled={busy} onClick={() => setPending('cancel')}>
            {t('organiser.cancelEvent')}
          </button>
        )}
        {isClosed && (
          <button className="btn-secondary" disabled={busy} onClick={() => setPending('archive')}>
            {t('organiser.archive')}
          </button>
        )}
        {isDraft && (
          <button className="btn-danger" disabled={busy} onClick={() => setPending('delete')}>
            {t('organiser.deleteDraft')}
          </button>
        )}
      </div>

      <ConfirmDialog
        open={pending === 'cancel'}
        title={t('organiser.cancelTitle')}
        description={t('organiser.cancelDesc')}
        reasonLabel={t('organiser.cancelReason')}
        reasonPlaceholder={t('organiser.cancelPlaceholder')}
        confirmLabel={t('organiser.cancelConfirm')}
        tone="danger"
        busy={busy}
        onCancel={() => setPending(null)}
        onConfirm={(reason) => run(() => api('POST', `/api/organiser/events/${event.id}/cancel`, { reason }), t('organiser.cancelled'))}
      />

      <ConfirmDialog
        open={pending === 'archive'}
        title={t('organiser.archiveTitle')}
        description={t('organiser.archiveDesc')}
        confirmLabel={t('organiser.archiveConfirm')}
        busy={busy}
        onCancel={() => setPending(null)}
        onConfirm={() => run(() => api('POST', `/api/organiser/events/${event.id}/archive`, { note: t('organiser.archiveNote') }), t('organiser.archived'))}
      />

      <ConfirmDialog
        open={pending === 'delete'}
        title={t('organiser.deleteTitle')}
        description={t('organiser.deleteDesc')}
        confirmLabel={t('organiser.deleteConfirm')}
        tone="danger"
        busy={busy}
        onCancel={() => setPending(null)}
        onConfirm={() => run(() => api('DELETE', `/api/organiser/events/${event.id}`), t('organiser.deleted'))}
      />
    </div>
  )
}
