import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, formatMoney, formatTime } from '../api'

interface EventListItem {
  id: string
  title: string
  category: string
  startsAt: string
  endsAt: string
  venueName: string | null
  city: string | null
  minPriceMinor: number | null
  currency: string | null
  available: number | null
}

interface SearchResult {
  items: EventListItem[]
  nextCursor: string | null
}

interface RecommendationPage {
  requestId: string
  modelVersion: string
  reasonCodes?: string[]
  items: Array<{
    eventId: string
    title: string
    category: string
    startsAt: string
    city: string | null
    score: number
    reasonCodes: string[]
  }>
}

function EventCard({ item }: { item: EventListItem }) {
  return (
    <Link to={`/events/${item.id}`} style={{ color: 'inherit', textDecoration: 'none' }}>
      <div className="event-card">
        <div className="row spread">
          <span className="badge accent">{item.category}</span>
          <span className="badge">{item.available != null && item.available > 0 ? `余票 ${item.available}` : '售罄'}</span>
        </div>
        <div className="title">{item.title}</div>
        <div className="meta">{formatTime(item.startsAt)}</div>
        <div className="meta">
          {item.venueName ?? ''} {item.city ? `· ${item.city}` : ''}
        </div>
        <div className="big-price">{formatMoney(item.minPriceMinor, item.currency ?? 'CNY')} 起</div>
      </div>
    </Link>
  )
}

export default function Discovery() {
  const [q, setQ] = useState('')
  const [category, setCategory] = useState('')
  const [city, setCity] = useState('')
  const [sort, setSort] = useState('starts_at')

  const search = useQuery({
    queryKey: ['events', q, category, city, sort],
    queryFn: () => {
      const params = new URLSearchParams()
      if (q) params.set('q', q)
      if (category) params.set('category', category)
      if (city) params.set('city', city)
      params.set('sort', sort)
      params.set('availableOnly', 'true')
      return api<SearchResult>('GET', `/api/v1/events?${params}`)
    },
  })

  const forYou = useQuery({
    queryKey: ['recs', 'for-you'],
    queryFn: () => api<RecommendationPage>('GET', '/api/v1/recommendations?section=for-you&limit=6'),
  })

  return (
    <>
      <div className="card">
        <h2>发现活动</h2>
        <div className="row">
          <input
            placeholder="搜索活动…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ flex: 2, minWidth: 180 }}
          />
          <select value={category} onChange={(e) => setCategory(e.target.value)} style={{ flex: 1 }}>
            <option value="">全部类别</option>
            <option value="music">音乐</option>
            <option value="tech">科技</option>
            <option value="sports">运动</option>
            <option value="art">艺术</option>
          </select>
          <input placeholder="城市" value={city} onChange={(e) => setCity(e.target.value)} style={{ flex: 1 }} />
          <select value={sort} onChange={(e) => setSort(e.target.value)} style={{ flex: 1 }}>
            <option value="starts_at">按开始时间</option>
            <option value="price">按价格</option>
            <option value="newest">最新发布</option>
          </select>
        </div>
        <p className="muted" style={{ fontSize: 12 }}>
          分页使用服务端签名的 keyset cursor：新写入不会破坏已遍历边界；页面余票为实时提示，结算时重新校验。
        </p>
      </div>

      {forYou.data && forYou.data.items.length > 0 && (
        <div className="card">
          <h3>为你推荐 <span className="badge accent">{forYou.data.modelVersion}</span></h3>
          <div className="grid">
            {forYou.data.items.map((item) => (
              <Link key={item.eventId} to={`/events/${item.eventId}`}
                style={{ color: 'inherit', textDecoration: 'none' }}>
                <div className="event-card">
                  <div className="title">{item.title}</div>
                  <div className="meta">{formatTime(item.startsAt)} {item.city ? `· ${item.city}` : ''}</div>
                  <div>
                    {item.reasonCodes.map((r) => (
                      <span key={r} className="reason">{r}</span>
                    ))}
                  </div>
                </div>
              </Link>
            ))}
          </div>
        </div>
      )}

      {search.isLoading && <p className="muted">加载中…</p>}
      {search.data && search.data.items.length === 0 && <p className="muted">没有符合条件的活动。</p>}
      <div className="grid">
        {search.data?.items.map((item) => <EventCard key={item.id} item={item} />)}
      </div>
    </>
  )
}
