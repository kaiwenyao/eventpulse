import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { api } from '../api'
import { formatDayTime } from '../lib/datetime'
import { EventVo, OrganiserDashboardVo, PageVo } from '../types'
import { EmptyState, ErrorNote, EventStatusBadge, SoldBar } from '../ui/Badges'
import { PlusIcon } from '../ui/Icons'
import { SkeletonLine } from '../ui/Skeleton'
import { Alert } from '../ui/Alert'
import { resolveApiError } from '../lib/apiError'

const RECENT_LIMIT = 5

interface StatCardProps {
  label: string
  value: string
  caption?: string
  tone?: 'default' | 'accent' | 'warn'
}

function StatCard({ label, value, caption, tone = 'default' }: StatCardProps) {
  return (
    <div className={`stat-card stat-${tone}`}>
      <p className="stat-label">{label}</p>
      <p className="stat-value">{value}</p>
      {caption && <p className="stat-caption muted">{caption}</p>}
    </div>
  )
}

export function OrganiserDashboardPage() {
  const { t } = useTranslation()
  const [dash, setDash] = useState<OrganiserDashboardVo | null>(null)
  const [recent, setRecent] = useState<EventVo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    Promise.all([
      api<OrganiserDashboardVo>('GET', '/api/organiser/dashboard').catch(() => null),
      api<PageVo<EventVo>>('GET', '/api/organiser/events?sort=startsAt').catch((e) => {
        setError(resolveApiError(e, 'organiser.loadFailed').message)
        return null
      }),
    ])
      .then(([dashboard, page]) => {
        setDash(dashboard)
        setRecent((page?.records ?? []).slice(0, RECENT_LIMIT))
      })
      .finally(() => setLoading(false))
  }, [t])

  const sellThrough = dash?.sellThrough ?? 0
  const lowStock = dash?.lowStock ?? []

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>{t('organiser.dashboardTitle')}</h1>
          <p className="muted">{t('organiser.dashboardSub')}</p>
        </div>
        <NavLink to="/organiser/events/new" className="btn-primary btn-link">
          <PlusIcon /> {t('organiser.newEvent')}
        </NavLink>
      </header>

      <ErrorNote message={error} />

      {loading ? (
        <div className="stat-grid">
          {Array.from({ length: 4 }, (_, i) => (
            <div key={i} className="stat-card">
              <SkeletonLine width="40%" />
              <SkeletonLine width="60%" />
            </div>
          ))}
        </div>
      ) : (
        <div className="stat-grid">
          <StatCard
            label={t('organiser.totalEvents')}
            value={String(dash?.eventCount ?? 0)}
            caption={t('organiser.publishedOf', { count: dash?.publishedCount ?? 0 })}
          />
          <StatCard
            label={t('organiser.soldCount')}
            value={String(dash?.sold ?? 0)}
            caption={t('organiser.capacityOf', { count: dash?.capacity ?? 0 })}
            tone="accent"
          />
          <StatCard label={t('organiser.sellThrough')} value={`${sellThrough.toFixed(1)}%`} caption={t('organiser.sellThroughCaption')} />
          <StatCard
            label={t('organiser.outbox')}
            value={String(dash?.outboxPending ?? 0)}
            caption={t('organiser.outboxCaption')}
            tone={(dash?.outboxPending ?? 0) > 0 ? 'warn' : 'default'}
          />
        </div>
      )}

      {lowStock.length > 0 && (
        <Alert tone="warn" title={t('organiser.lowStock')}>
          <p className="muted">{lowStock.join('、')}</p>
        </Alert>
      )}

      <section>
        <div className="section-head">
          <h2 className="section-title">{t('organiser.recent')}</h2>
          <NavLink to="/organiser/events">{t('organiser.viewAll')}</NavLink>
        </div>
        {recent.length === 0 ? (
          <EmptyState
            title={t('organiser.emptyEventsTitle')}
            hint={t('organiser.emptyEventsHint')}
            action={
              <NavLink to="/organiser/events/new" className="btn-primary btn-link">
                {t('organiser.newEvent')}
              </NavLink>
            }
          />
        ) : (
          <ul className="stack-list">
            {recent.map((event) => (
              <li key={event.id} className="card booking-row">
                <div className="booking-copy">
                  <h3>
                    <NavLink to={`/organiser/events/${event.id}`}>{event.title}</NavLink>
                  </h3>
                  <p className="muted small">
                    {event.city} · {formatDayTime(event.startsAt)} · {t('organiser.soldLine', { sold: event.sold, capacity: event.capacity })}
                  </p>
                  <SoldBar sold={event.sold} capacity={event.capacity} />
                </div>
                <EventStatusBadge status={event.status} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
