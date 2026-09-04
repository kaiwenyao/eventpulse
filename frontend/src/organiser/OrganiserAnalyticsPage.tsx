import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api'
import { EventVo, PageVo } from '../types'
import { EmptyState, ErrorNote } from '../ui/Badges'
import { Select } from '../ui/Select'
import { SkeletonCard } from '../ui/Skeleton'
import { resolveApiError } from '../lib/apiError'

interface MetricRow {
  metricDate: string
  views: number
  clicks: number
  bookings: number
}

interface AnalyticsVo {
  views?: number
  clicks?: number
  bookings?: number
  tickets?: number
  conversion?: number
  series?: MetricRow[]
}

/** Daily-views column chart. Plain divs — a chart library is not worth 40 KB here. */
function ViewsChart({ series }: { series: MetricRow[] }) {
  const { t } = useTranslation()
  const peak = Math.max(1, ...series.map((row) => row.views))
  return (
    <div className="chart" role="img" aria-label={t('organiser.trendAria', { days: series.length })}>
      {series.map((row) => (
        <div key={row.metricDate} className={`chart-col ${row.views === peak ? 'chart-col-peak' : ''}`}>
          <span className="chart-bar" style={{ height: `${Math.max(2, (row.views / peak) * 100)}%` }} />
          <span className="chart-tick">{row.metricDate?.slice(5)}</span>
        </div>
      ))}
    </div>
  )
}

export function OrganiserAnalyticsPage() {
  const { t } = useTranslation()
  const [data, setData] = useState<AnalyticsVo | null>(null)
  const [events, setEvents] = useState<EventVo[]>([])
  const [eventId, setEventId] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    api<PageVo<EventVo>>('GET', '/api/organiser/events')
      .then((page) => setEvents(page?.records ?? []))
      .catch(() => setEvents([]))
  }, [])

  useEffect(() => {
    const path = eventId ? `/api/organiser/analytics?eventId=${eventId}` : '/api/organiser/analytics'
    api<AnalyticsVo>('GET', path)
      .then((next) => {
        setData(next)
        setError('')
      })
      .catch((e) => setError(resolveApiError(e, 'common.loadFailed').message))
      .finally(() => setLoading(false))
  }, [eventId, t])

  const tiles = useMemo(
    () => [
      { label: t('organiser.views'), value: String(data?.views ?? 0), caption: t('organiser.viewsCaption'), tone: '' },
      { label: t('organiser.clicks'), value: String(data?.clicks ?? 0), caption: t('organiser.clicksCaption'), tone: '' },
      { label: t('organiser.bookings'), value: String(data?.bookings ?? 0), caption: t('organiser.bookingsCaption'), tone: '' },
      {
        label: t('organiser.conversion'),
        value: `${Number(data?.conversion ?? 0).toFixed(1)}%`,
        caption: t('organiser.conversionCaption'),
        tone: 'stat-accent',
      },
    ],
    [data, t],
  )

  const series = data?.series ?? []

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>{t('organiser.analyticsTitle')}</h1>
          <p className="muted">{t('organiser.analyticsSub')}</p>
        </div>
        <Select
          className="analytics-picker"
          aria-label={t('organiser.pickEvent')}
          value={eventId}
          onChange={setEventId}
          options={[
            { value: '', label: t('organiser.allEvents') },
            ...events.map((event) => ({ value: String(event.id), label: event.title })),
          ]}
        />
      </header>

      <ErrorNote message={error} />

      {loading ? (
        <SkeletonCard />
      ) : (
        <>
          <div className="stat-grid">
            {tiles.map((tile) => (
              <div key={tile.label} className={`stat-card ${tile.tone}`}>
                <p className="stat-label">{tile.label}</p>
                <p className="stat-value">{tile.value}</p>
                <p className="stat-caption muted">{tile.caption}</p>
              </div>
            ))}
          </div>

          <section>
            <h2 className="section-title">{t('organiser.trend')}</h2>
            {series.length === 0 ? (
              <EmptyState title={t('organiser.noTrendTitle')} hint={t('organiser.noTrendHint')} />
            ) : (
              <ViewsChart series={series} />
            )}
          </section>
        </>
      )}
    </div>
  )
}
