import { useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { api, ApiError } from '../api'
import { formatDayTime } from '../lib/datetime'
import { EventVo, OrganiserDashboardVo, PageVo } from '../types'
import { EmptyState, ErrorNote, EventStatusBadge, SoldBar } from '../ui/Badges'
import { PlusIcon } from '../ui/Icons'
import { SkeletonLine } from '../ui/Skeleton'

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
  const [dash, setDash] = useState<OrganiserDashboardVo | null>(null)
  const [recent, setRecent] = useState<EventVo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    Promise.all([
      api<OrganiserDashboardVo>('GET', '/api/organiser/dashboard').catch(() => null),
      api<PageVo<EventVo>>('GET', '/api/organiser/events?sort=startsAt').catch((e) => {
        setError(e instanceof ApiError ? e.message : '加载活动失败')
        return null
      }),
    ])
      .then(([dashboard, page]) => {
        setDash(dashboard)
        setRecent((page?.records ?? []).slice(0, RECENT_LIMIT))
      })
      .finally(() => setLoading(false))
  }, [])

  const sellThrough = dash?.sellThrough ?? 0
  const lowStock = dash?.lowStock ?? []

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>主办方工作台</h1>
          <p className="muted">票务健康度、待办与最近活动的一站式概览。</p>
        </div>
        <NavLink to="/organiser/events/new" className="btn-primary btn-link">
          <PlusIcon /> 新建活动
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
            label="活动总数"
            value={String(dash?.eventCount ?? 0)}
            caption={`其中 ${dash?.publishedCount ?? 0} 场已发布`}
          />
          <StatCard label="已售票数" value={String(dash?.sold ?? 0)} caption={`总库存 ${dash?.capacity ?? 0} 张`} tone="accent" />
          <StatCard label="售票率" value={`${sellThrough.toFixed(1)}%`} caption="已售 / 总库存" />
          <StatCard
            label="待投递事件"
            value={String(dash?.outboxPending ?? 0)}
            caption="Outbox 中未推送的领域事件"
            tone={(dash?.outboxPending ?? 0) > 0 ? 'warn' : 'default'}
          />
        </div>
      )}

      {lowStock.length > 0 && (
        <div className="callout callout-warn">
          <p className="callout-title">余票告急（≤ 5 张）</p>
          <p className="muted">{lowStock.join('、')}</p>
        </div>
      )}

      <section>
        <div className="section-head">
          <h2 className="section-title">最近活动</h2>
          <NavLink to="/organiser/events">查看全部</NavLink>
        </div>
        {recent.length === 0 ? (
          <EmptyState
            title="还没有活动"
            hint="创建第一场活动，可以先保存草稿再发布。"
            action={
              <NavLink to="/organiser/events/new" className="btn-primary btn-link">
                新建活动
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
                    {event.city} · {formatDayTime(event.startsAt)} · 已售 {event.sold}/{event.capacity}
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
