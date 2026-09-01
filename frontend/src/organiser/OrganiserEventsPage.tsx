import { useEffect, useMemo, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { api, ApiError, formatMoney } from '../api'
import { formatDayTime } from '../lib/datetime'
import { EVENT_STATUSES, EventVo, PageVo } from '../types'
import { CategoryPill, EmptyState, ErrorNote, EventStatusBadge, SoldBar } from '../ui/Badges'
import { PlusIcon } from '../ui/Icons'
import { SkeletonCard } from '../ui/Skeleton'

export function OrganiserEventsPage() {
  const [mine, setMine] = useState<EventVo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')

  useEffect(() => {
    api<PageVo<EventVo>>('GET', `/api/organiser/events?q=${encodeURIComponent(q)}&status=${status}`)
      .then((page) => {
        setMine(page?.records ?? [])
        setError('')
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : '加载活动失败'))
      .finally(() => setLoading(false))
  }, [q, status])

  const summary = useMemo(() => {
    const sold = mine.reduce((sum, e) => sum + e.sold, 0)
    const capacity = mine.reduce((sum, e) => sum + e.capacity, 0)
    return { sold, capacity }
  }, [mine])

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>活动管理</h1>
          <p className="muted">
            共 {mine.length} 场活动 · 已售 {summary.sold}/{summary.capacity} 张
          </p>
        </div>
        <NavLink to="/organiser/events/new" className="btn-primary btn-link">
          <PlusIcon /> 新建活动
        </NavLink>
      </header>

      <ErrorNote message={error} />

      <div className="search-row toolbar">
        <input
          className="search"
          placeholder="搜索我的活动…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          aria-label="搜索我的活动"
        />
        <select aria-label="状态筛选" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">全部状态</option>
          {EVENT_STATUSES.map((s) => (
            <option key={s.key} value={s.key}>
              {s.label}
            </option>
          ))}
        </select>
      </div>

      {loading ? (
        <SkeletonCard />
      ) : mine.length === 0 ? (
        <EmptyState
          title="没有匹配的活动"
          hint="换个关键词或状态筛选，也可以直接创建一场新活动。"
          action={
            <NavLink to="/organiser/events/new" className="btn-primary btn-link">
              新建活动
            </NavLink>
          }
        />
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">活动</th>
                <th scope="col">状态</th>
                <th scope="col">开始时间</th>
                <th scope="col">票价</th>
                <th scope="col">售出</th>
                <th scope="col">操作</th>
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
                  <td className="num">{event.priceCents === 0 ? '免费' : formatMoney(event.priceCents)}</td>
                  <td className="cell-sold">
                    <SoldBar sold={event.sold} capacity={event.capacity} />
                  </td>
                  <td>
                    <div className="row table-actions">
                      <NavLink to={`/organiser/events/${event.id}`}>查看</NavLink>
                      <NavLink to={`/organiser/events/${event.id}/edit`}>编辑</NavLink>
                      <NavLink to={`/organiser/events/${event.id}/attendees`}>参与者</NavLink>
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
