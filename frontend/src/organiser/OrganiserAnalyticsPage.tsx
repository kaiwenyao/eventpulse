import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api'
import { EventVo, PageVo } from '../types'
import { EmptyState, ErrorNote } from '../ui/Badges'
import { SkeletonCard } from '../ui/Skeleton'

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
  const peak = Math.max(1, ...series.map((row) => row.views))
  return (
    <div className="chart" role="img" aria-label={`最近 ${series.length} 天的每日浏览量`}>
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
      .catch((e) => setError(e instanceof ApiError ? e.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [eventId])

  const tiles = useMemo(
    () => [
      { label: '浏览', value: String(data?.views ?? 0), caption: '活动详情页曝光', tone: '' },
      { label: '点击', value: String(data?.clicks ?? 0), caption: '进入预订流程', tone: '' },
      { label: '预订', value: String(data?.bookings ?? 0), caption: '成功创建的订单', tone: '' },
      {
        label: '转化',
        value: `${Number(data?.conversion ?? 0).toFixed(1)}%`,
        caption: '预订 / 浏览',
        tone: 'stat-accent',
      },
    ],
    [data],
  )

  const series = data?.series ?? []

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>数据分析</h1>
          <p className="muted">默认展示最近 14 天，选择具体活动可查看单场趋势。</p>
        </div>
        <select
          aria-label="选择活动"
          value={eventId}
          onChange={(e) => setEventId(e.target.value)}
          className="analytics-picker"
        >
          <option value="">全部活动</option>
          {events.map((event) => (
            <option key={event.id} value={event.id}>
              {event.title}
            </option>
          ))}
        </select>
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
            <h2 className="section-title">每日浏览趋势</h2>
            {series.length === 0 ? (
              <EmptyState title="暂无趋势数据" hint="选择一场具体活动，或等待今天的指标落库。" />
            ) : (
              <ViewsChart series={series} />
            )}
          </section>
        </>
      )}
    </div>
  )
}
