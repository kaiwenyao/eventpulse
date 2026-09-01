import { useEffect, useMemo, useState } from 'react'
import { api } from '../api'
import { EventTicket } from '../components/EventTicket'
import { CATEGORIES, EventVo } from '../types'
import { EmptyState } from '../ui/Badges'
import { SkeletonGrid } from '../ui/Skeleton'

type DiscoveryMode = 'list' | 'nearby' | 'recommend'

/** Shanghai city centre — the demo origin for the "附近" radius query. */
const NEARBY_ORIGIN = { lat: 31.23, lng: 121.47, radiusKm: 30 }

function buildPath(mode: DiscoveryMode, params: URLSearchParams) {
  if (mode === 'nearby') {
    return `/api/events/nearby?lat=${NEARBY_ORIGIN.lat}&lng=${NEARBY_ORIGIN.lng}&radiusKm=${NEARBY_ORIGIN.radiusKm}`
  }
  if (mode === 'recommend') return '/api/recommendations'
  return `/api/events${params.size ? `?${params}` : ''}`
}

export function EventsPage() {
  const [events, setEvents] = useState<EventVo[]>([])
  const [loading, setLoading] = useState(true)
  const [q, setQ] = useState('')
  const [cat, setCat] = useState('')
  const [city, setCity] = useState('')
  const [sort, setSort] = useState('startsAt')
  const [mode, setMode] = useState<DiscoveryMode>('list')

  useEffect(() => {
    const params = new URLSearchParams()
    if (q) params.set('q', q)
    if (cat) params.set('category', cat)
    if (city) params.set('city', city)
    if (sort) params.set('sort', sort)
    let cancelled = false
    api<EventVo[]>('GET', buildPath(mode, params))
      .then((data) => {
        if (!cancelled) setEvents(Array.isArray(data) ? data : [])
      })
      .catch(() => {
        if (!cancelled) setEvents([])
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [q, cat, city, sort, mode])

  const stats = useMemo(() => {
    const cities = new Set(events.map((e) => e.city).filter(Boolean))
    const remaining = events.reduce((sum, e) => sum + Math.max(e.capacity - e.sold, 0), 0)
    return { count: events.length, cities: cities.size, remaining }
  }, [events])

  return (
    <div>
      <section className="hero">
        <div className="hero-copy">
          <p className="eyebrow">EventPulse · 城市活动预订</p>
          <h1>发现今晚的城市脉搏</h1>
          <p className="muted hero-sub">音乐、科技、运动、艺术 —— 找到你的下一张票。</p>
        </div>
        <dl className="hero-stats">
          <div>
            <dt>在售活动</dt>
            <dd>{stats.count}</dd>
          </div>
          <div>
            <dt>覆盖城市</dt>
            <dd>{stats.cities}</dd>
          </div>
          <div>
            <dt>可购票数</dt>
            <dd>{stats.remaining}</dd>
          </div>
        </dl>
      </section>

      <div className="search-row">
        <input
          className="search"
          placeholder="搜索活动…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          aria-label="搜索活动"
        />
        <div className="chips" role="group" aria-label="按分类筛选">
          <button className={`chip ${cat === '' ? 'active' : ''}`} onClick={() => setCat('')}>
            全部
          </button>
          {CATEGORIES.map((c) => (
            <button key={c.key} className={`chip ${cat === c.key ? 'active' : ''}`} onClick={() => setCat(c.key)}>
              {c.label}
            </button>
          ))}
        </div>
        <input
          className="search search-city"
          placeholder="城市"
          value={city}
          onChange={(e) => setCity(e.target.value)}
          aria-label="城市"
        />
        <select aria-label="排序" value={sort} onChange={(e) => setSort(e.target.value)}>
          <option value="startsAt">开始时间</option>
          <option value="price">票价</option>
          <option value="sold">热度</option>
        </select>
        <div className="chips" role="group" aria-label="切换发现模式">
          <button
            className={`chip ${mode === 'nearby' ? 'active' : ''}`}
            onClick={() => setMode(mode === 'nearby' ? 'list' : 'nearby')}
          >
            附近
          </button>
          <button
            className={`chip ${mode === 'recommend' ? 'active' : ''}`}
            onClick={() => setMode(mode === 'recommend' ? 'list' : 'recommend')}
          >
            推荐
          </button>
        </div>
      </div>

      {loading ? (
        <SkeletonGrid label="正在加载活动" />
      ) : events.length === 0 ? (
        <EmptyState title="还没有活动" hint="换个关键词或分类试试，也可以切换其他城市。" />
      ) : (
        <div className="grid">
          {events.map((event) => (
            <EventTicket key={event.id} event={event} />
          ))}
        </div>
      )}
    </div>
  )
}
