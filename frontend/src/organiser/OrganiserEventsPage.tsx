import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { api, formatMoney } from '../api'
import { formatDayTime } from '../lib/datetime'
import { CATEGORIES, EVENT_STATUSES, EventVo, PageVo } from '../types'
import { CategoryPill, EmptyState, ErrorNote, EventStatusBadge, SoldBar } from '../ui/Badges'
import { PlusIcon } from '../ui/Icons'
import { SkeletonCard } from '../ui/Skeleton'
import { resolveApiError } from '../lib/apiError'

export function OrganiserEventsPage() {
  const { t } = useTranslation()
  const [mine, setMine] = useState<EventVo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')
  const [category, setCategory] = useState('')

  useEffect(() => {
    api<PageVo<EventVo>>(
      'GET',
      `/api/organiser/events?q=${encodeURIComponent(q)}&status=${status}&category=${category}`,
    )
      .then((page) => {
        setMine(page?.records ?? [])
        setError('')
      })
      .catch((e) => setError(resolveApiError(e, 'organiser.loadFailed').message))
      .finally(() => setLoading(false))
  }, [q, status, category, t])

  const summary = useMemo(() => {
    const sold = mine.reduce((sum, e) => sum + e.sold, 0)
    const capacity = mine.reduce((sum, e) => sum + e.capacity, 0)
    return { sold, capacity }
  }, [mine])

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>{t('organiser.events')}</h1>
          <p className="muted">{t('organiser.eventsCount', { count: mine.length, sold: summary.sold, capacity: summary.capacity })}</p>
        </div>
        <NavLink to="/organiser/events/new" className="btn-primary btn-link">
          <PlusIcon /> {t('organiser.newEvent')}
        </NavLink>
      </header>

      <ErrorNote message={error} />

      <div className="search-row toolbar">
        <input
          className="search"
          placeholder={t('organiser.searchMine')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          aria-label={t('organiser.searchMineAria')}
        />
        <select aria-label={t('organiser.statusFilter')} value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">{t('organiser.allStatuses')}</option>
          {EVENT_STATUSES.map((s) => (
            <option key={s.key} value={s.key}>
              {t(`status.event.${s.key}`)}
            </option>
          ))}
        </select>
        <select
          aria-label={t('events.filterCategory')}
          value={category}
          onChange={(e) => setCategory(e.target.value)}
        >
          <option value="">{t('events.allCategories')}</option>
          {CATEGORIES.map((c) => (
            <option key={c.key} value={c.key}>
              {t(`category.${c.key}`)}
            </option>
          ))}
        </select>
      </div>

      {loading ? (
        <SkeletonCard />
      ) : mine.length === 0 ? (
        <EmptyState
          title={t('organiser.noMatchTitle')}
          hint={t('organiser.noMatchHint')}
          action={
            <NavLink to="/organiser/events/new" className="btn-primary btn-link">
              {t('organiser.newEvent')}
            </NavLink>
          }
        />
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">{t('organiser.colEvent')}</th>
                <th scope="col">{t('organiser.colStatus')}</th>
                <th scope="col">{t('organiser.colStart')}</th>
                <th scope="col">{t('organiser.colPrice')}</th>
                <th scope="col">{t('organiser.colSold')}</th>
                <th scope="col">{t('organiser.colActions')}</th>
              </tr>
            </thead>
            <tbody>
              {mine.map((event) => (
                <tr key={event.id}>
                  <th scope="row">
                    <NavLink to={`/organiser/events/${event.id}`} className="table-title">
                      {event.title}
                    </NavLink>
                    <span className="row table-sub">
                      <CategoryPill category={event.category} />
                      <span className="muted small">{event.city}</span>
                    </span>
                  </th>
                  <td>
                    <EventStatusBadge status={event.status} />
                  </td>
                  <td className="num">{formatDayTime(event.startsAt)}</td>
                  <td className="num">{event.priceCents === 0 ? t('common.free') : formatMoney(event.priceCents)}</td>
                  <td className="cell-sold">
                    <SoldBar sold={event.sold} capacity={event.capacity} />
                  </td>
                  <td>
                    <div className="row table-actions">
                      <NavLink to={`/organiser/events/${event.id}`}>{t('organiser.view')}</NavLink>
                      <NavLink to={`/organiser/events/${event.id}/edit`}>{t('organiser.edit')}</NavLink>
                      <NavLink to={`/organiser/events/${event.id}/attendees`}>{t('organiser.attendees')}</NavLink>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
