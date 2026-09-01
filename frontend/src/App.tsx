import { FormEvent, useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, formatMoney, formatTime } from './api'
import { useAuth } from './auth'

interface EventVo {
  id: number
  title: string
  summary?: string
  description: string
  category: string
  city: string
  venueName?: string
  address?: string
  startsAt: string
  endsAt?: string
  coverUrl?: string
  priceCents: number
  capacity: number
  sold: number
  remaining: number
  status: string
  maxQuantityPerBooking?: number
  favourite?: boolean
  bookable?: boolean
  unbookableReason?: string
  version?: number
  contactInfo?: string
  attendanceNotes?: string
  cancellationReason?: string
}

interface BookingVo {
  id: number
  eventId: number
  eventTitle: string
  quantity: number
  status: string
  createdAt: string
}

interface NotificationVo {
  id: number
  bookingId?: number
  type?: string
  title?: string
  message: string
  createdAt: string
}

const CATEGORIES = [
  { key: 'music', label: '音乐' },
  { key: 'tech', label: '科技' },
  { key: 'sports', label: '运动' },
  { key: 'art', label: '艺术' },
] as const

const CATEGORY_LABELS: Record<string, string> = Object.fromEntries(CATEGORIES.map((c) => [c.key, c.label]))

function CategoryPill({ category }: { category: string }) {
  const label = CATEGORY_LABELS[category] ?? category
  return <span className={`pill pill-${category}`}>{label}</span>
}

function SoldBar({ sold, capacity }: { sold: number; capacity: number }) {
  const ratio = capacity > 0 ? Math.min(sold / capacity, 1) : 0
  const level = ratio >= 0.85 ? 'hot' : ratio >= 0.5 ? 'mid' : 'low'
  return (
    <div className="sold">
      <div className={`sold-track ${level}`}>
        <span className="sold-fill" style={{ width: `${ratio * 100}%` }} />
      </div>
      <span className="sold-label">{capacity - sold} 张余票</span>
    </div>
  )
}

function EmptyState({ title, hint }: { title: string; hint: string }) {
  return (
    <div className="empty">
      <div className="empty-dot" aria-hidden />
      <p className="empty-title">{title}</p>
      <p className="muted">{hint}</p>
    </div>
  )
}

function TopBar() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  return (
    <header className="topbar">
      <NavLink to="/" className="brand">
        Event<span>Pulse</span>
      </NavLink>
      <nav>
        <NavLink to="/">活动</NavLink>
        {user && <NavLink to="/bookings">我的预订</NavLink>}
        {user && <NavLink to="/favourites">收藏</NavLink>}
        {user && <NavLink to="/notifications">消息</NavLink>}
        {user?.role === 'ORGANISER' && <NavLink to="/organiser">工作台</NavLink>}
      </nav>
      {user ? (
        <span className="row user-box">
          <span className="muted hide-sm">{user.email}</span>
          <button
            className="btn-secondary"
            onClick={() => {
              logout()
              navigate('/')
            }}
          >
            退出
          </button>
        </span>
      ) : (
        <NavLink to="/login" className="btn-ghost">
          登录 / 注册
        </NavLink>
      )}
    </header>
  )
}

function EventsPage() {
  const [events, setEvents] = useState<EventVo[]>([])
  const [q, setQ] = useState('')
  const [cat, setCat] = useState('')
  const [city, setCity] = useState('')
  const [sort, setSort] = useState('startsAt')
  const [mode, setMode] = useState<'list' | 'nearby' | 'recommend'>('list')
  useEffect(() => {
    const params = new URLSearchParams()
    if (q) params.set('q', q)
    if (cat) params.set('category', cat)
    if (city) params.set('city', city)
    if (sort) params.set('sort', sort)
    const path =
      mode === 'nearby'
        ? '/api/events/nearby?lat=31.23&lng=121.47&radiusKm=30'
        : mode === 'recommend'
          ? '/api/recommendations'
          : `/api/events${params.size ? `?${params}` : ''}`
    api<EventVo[]>('GET', path)
      .then(setEvents)
      .catch(() => setEvents([]))
  }, [q, cat, city, sort, mode])
  return (
    <div>
      <section className="hero">
        <p className="eyebrow">EventPulse · 城市活动预订</p>
        <h1>发现今晚的城市脉搏</h1>
        <p className="muted hero-sub">音乐、科技、运动、艺术 —— 找到你的下一张票。</p>
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
        <input className="search" placeholder="城市" value={city} onChange={(e) => setCity(e.target.value)} aria-label="城市" />
        <select aria-label="排序" value={sort} onChange={(e) => setSort(e.target.value)}>
          <option value="startsAt">开始时间</option>
          <option value="price">票价</option>
          <option value="sold">热度</option>
        </select>
        <button className={`chip ${mode === 'nearby' ? 'active' : ''}`} onClick={() => setMode(mode === 'nearby' ? 'list' : 'nearby')}>
          附近
        </button>
        <button className={`chip ${mode === 'recommend' ? 'active' : ''}`} onClick={() => setMode(mode === 'recommend' ? 'list' : 'recommend')}>
          推荐
        </button>
      </div>
      {events.length === 0 ? (
        <EmptyState title="还没有活动" hint="换个关键词或分类试试，也可以切换其他城市。" />
      ) : (
        <div className="grid">
          {events.map((event) => (
            <NavLink key={event.id} to={`/events/${event.id}`} className="ticket">
              <div className="ticket-main">
                <div className="ticket-head">
                  <CategoryPill category={event.category} />
                  <span className="ticket-city">{event.city}</span>
                </div>
                <h2 className="ticket-title">{event.title}</h2>
                <p className="ticket-time">
                  <svg viewBox="0 0 16 16" aria-hidden focusable="false">
                    <circle cx="8" cy="8" r="6.5" />
                    <path d="M8 4.5V8l2.5 1.5" />
                  </svg>
                  {formatTime(event.startsAt)}
                </p>
                <SoldBar sold={event.sold} capacity={event.capacity} />
              </div>
              <div className="ticket-stub">
                <span className="stub-price">{event.priceCents === 0 ? '免费' : formatMoney(event.priceCents)}</span>
                <span className="stub-caption">预订</span>
                <svg className="stub-arrow" viewBox="0 0 16 16" aria-hidden focusable="false">
                  <path d="M2 8h12M10 4l4 4-4 4" />
                </svg>
              </div>
            </NavLink>
          ))}
        </div>
      )}
    </div>
  )
}

function EventDetailPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const [event, setEvent] = useState<EventVo | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    api<EventVo>('GET', `/api/events/${id}`).then(setEvent).catch(() => setError('活动不存在'))
  }, [id])
  if (error)
    return (
      <EmptyState
        title={error}
        hint="这个活动可能已下架，或链接有误。"
      />
    )
  if (!event) return <p className="muted">加载中…</p>
  return (
    <article className="card detail-card">
      <div className="detail-top">
        <CategoryPill category={event.category} />
        <span className="ticket-city">{event.city}</span>
      </div>
      <h1>{event.title}</h1>
      <p className="muted detail-meta">
        <svg viewBox="0 0 16 16" aria-hidden focusable="false">
          <circle cx="8" cy="8" r="6.5" />
          <path d="M8 4.5V8l2.5 1.5" />
        </svg>
        {formatTime(event.startsAt)}
      </p>
      <p className="detail-desc">{event.description}</p>
      {event.venueName && <p className="muted">{event.venueName} · {event.address}</p>}
      {user && (
        <button
          className="btn-secondary"
          onClick={async () => {
            if (event.favourite) await api('DELETE', `/api/events/${event.id}/favourite`)
            else await api('POST', `/api/events/${event.id}/favourite`)
            setEvent({ ...event, favourite: !event.favourite })
          }}
        >
          {event.favourite ? '取消收藏' : '收藏活动'}
        </button>
      )}
      <div className="detail-side">
        <div className="detail-price">{event.priceCents === 0 ? '免费' : formatMoney(event.priceCents)}</div>
        <SoldBar sold={event.sold} capacity={event.capacity} />
        {user ? (
          <BookingBox event={event} onError={setError} />
        ) : (
          <NavLink to="/login" className="btn-ghost">
            登录后预订
          </NavLink>
        )}
        {error && <p className="error-text">{error}</p>}
      </div>
    </article>
  )
}

function LoginPage() {
  const { login, register } = useAuth()
  const navigate = useNavigate()
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    try {
      if (mode === 'login') await login(email, password)
      else await register(email, password, name)
      navigate('/')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '失败')
    }
  }
  return (
    <form className="card auth-card" onSubmit={onSubmit}>
      <p className="eyebrow">EventPulse</p>
      <h1>{mode === 'login' ? '登录' : '注册'}</h1>
      <label htmlFor="auth-email">邮箱</label>
      <input id="auth-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      <label htmlFor="auth-password">密码</label>
      <input id="auth-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      {mode === 'register' && (
        <>
          <label htmlFor="auth-name">昵称</label>
          <input id="auth-name" value={name} onChange={(e) => setName(e.target.value)} required />
        </>
      )}
      {error && <p className="error-text">{error}</p>}
      <button type="submit" className="btn-primary btn-block">
        {mode === 'login' ? '登录' : '注册'}
      </button>
      <button type="button" className="btn-secondary btn-block" onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
        {mode === 'login' ? '去注册' : '去登录'}
      </button>
      <p className="muted demo-hint">
        演示账号 user@eventpulse.dev / User123456 ，主办方 organiser@eventpulse.dev / Organiser123456
      </p>
    </form>
  )
}

function BookingsPage() {
  const [items, setItems] = useState<BookingVo[]>([])
  useEffect(() => {
    api<BookingVo[]>('GET', '/api/bookings').then(setItems)
  }, [])
  return (
    <div>
      <h1>我的预订</h1>
      {items.length === 0 ? (
        <EmptyState title="还没有预订" hint="去活动页挑一张喜欢的票吧。" />
      ) : (
        items.map((b) => (
          <div key={b.id} className="card booking-row">
            <div>
              <h3>
                <NavLink to={`/bookings/${b.id}`}>{b.eventTitle}</NavLink>
              </h3>
              <p className="muted">
                {b.quantity} 张 · {b.status}
              </p>
            </div>
            {b.status === 'CONFIRMED' && (
              <button
                className="btn-secondary"
                onClick={async () => {
                  const updated = await api<BookingVo>('POST', `/api/bookings/${b.id}/cancel`)
                  setItems((prev) => prev.map((x) => (x.id === b.id ? updated : x)))
                }}
              >
                取消
              </button>
            )}
          </div>
        ))
      )}
    </div>
  )
}

function NotificationsPage() {
  const [items, setItems] = useState<NotificationVo[]>([])
  useEffect(() => {
    api<NotificationVo[]>('GET', '/api/notifications').then(setItems)
  }, [])
  return (
    <div>
      <h1>消息中心</h1>
      <p className="muted">预订、变更、取消和提醒都会出现在这里。</p>
      {items.length === 0 ? (
        <EmptyState title="还没有消息" hint="预订一场活动后，通知会送到这里。" />
      ) : (
        items.map((n) => (
          <div key={n.id} className="card notification-row">
            <span className="notification-dot" aria-hidden />
            <div>
              <p>{n.title ? `${n.title} · ${n.message}` : n.message}</p>
              <p className="muted small">{formatTime(n.createdAt)}</p>
              <button className="btn-secondary" onClick={() => api('POST', `/api/notifications/${n.id}/read`).then(() => setItems((prev) => prev.filter((x) => x.id !== n.id)))}>
                标为已读
              </button>
            </div>
          </div>
        ))
      )}
    </div>
  )
}

function BookingBox({ event, onError }: { event: EventVo; onError: (msg: string) => void }) {
  const navigate = useNavigate()
  const maxQty = event.maxQuantityPerBooking ?? 10
  const [qty, setQty] = useState(1)
  return (
    <div>
      <label htmlFor="book-qty">预订数量</label>
      <input
        id="book-qty"
        type="number"
        min={1}
        max={Math.min(maxQty, event.remaining)}
        value={qty}
        onChange={(e) => setQty(Number(e.target.value))}
      />
      <p className="muted">
        {qty} 张 · {formatMoney(event.priceCents * qty)}
      </p>
      <button
        onClick={async () => {
          try {
            const booking = await api<{ id: number }>('POST', '/api/bookings', { eventId: event.id, quantity: qty })
            navigate(`/bookings/${booking.id}`)
          } catch (e) {
            onError(e instanceof ApiError ? e.message : '预订失败')
          }
        }}
      >
        确认预订
      </button>
    </div>
  )
}

function TicketQr({ code }: { code: string }) {
  const cells = 17
  const bits: boolean[] = []
  let h = 0
  for (let i = 0; i < code.length; i++) h = (h * 33 + code.charCodeAt(i)) >>> 0
  for (let i = 0; i < cells * cells; i++) {
    h = (h * 1664525 + 1013904223) >>> 0
    bits.push(h % 3 === 0)
  }
  return (
    <svg className="qr" viewBox={`0 0 ${cells} ${cells}`} role="img" aria-label={`票码 ${code}`}>
      {bits.map((on, i) =>
        on ? <rect key={i} x={i % cells} y={Math.floor(i / cells)} width="1" height="1" fill="currentColor" /> : null,
      )}
    </svg>
  )
}

function BookingDetailPage() {
  const { id } = useParams()
  const [booking, setBooking] = useState<BookingVo | null>(null)
  const [tickets, setTickets] = useState<{ id: number; code?: string; status: string }[]>([])
  const [error, setError] = useState('')
  useEffect(() => {
    if (!id) return
    api<BookingVo>('GET', `/api/bookings/${id}`).then(setBooking).catch(() => setError('订单不存在'))
    api<{ id: number; code?: string; status: string }[]>('GET', `/api/bookings/${id}/tickets`)
      .then(setTickets)
      .catch(() => setTickets([]))
  }, [id])
  if (error) return <EmptyState title={error} hint="这个订单可能不属于你。" />
  if (!booking) return <p className="muted">加载中…</p>
  return (
    <div>
      <h1>订单详情</h1>
      <div className="card">
        <h2>{booking.eventTitle}</h2>
        <p className="muted">
          {booking.quantity} 张 · {booking.status}
        </p>
        {booking.status === 'CONFIRMED' && (
          <button
            className="btn-secondary"
            onClick={async () => {
              const updated = await api<BookingVo>('POST', `/api/bookings/${booking.id}/cancel`)
              setBooking(updated)
            }}
          >
            取消订单
          </button>
        )}
      </div>
      {tickets.map((ticket) => (
        <div key={ticket.id} className="card">
          {ticket.code && <TicketQr code={ticket.code} />}
          <p>票 #{ticket.id} · {ticket.status}</p>
          {ticket.code && <p className="muted">{ticket.code}</p>}
        </div>
      ))}
    </div>
  )
}

function FavouritesPage() {
  const [items, setItems] = useState<EventVo[]>([])
  useEffect(() => {
    api<{ records: EventVo[] }>('GET', '/api/favourites')
      .then((page) => setItems(page.records ?? []))
      .catch(() => setItems([]))
  }, [])
  return (
    <div>
      <h1>我的收藏</h1>
      {items.length === 0 ? (
        <EmptyState title="还没有收藏" hint="在活动详情页点击收藏，方便下次找回来。" />
      ) : (
        items.map((event) => (
          <NavLink key={event.id} to={`/events/${event.id}`} className="card booking-row">
            <h3>{event.title}</h3>
            <p className="muted">{event.city}</p>
          </NavLink>
        ))
      )}
    </div>
  )
}

function OrganiserGate() {
  const { user } = useAuth()
  if (user?.role !== 'ORGANISER') {
    return <EmptyState title="没有主办方权限" hint="请使用主办方账号登录后再进入工作台。" />
  }
  return (
    <Routes>
      <Route path="/" element={<OrganiserPage />} />
      <Route path="events" element={<OrganiserEventsPage />} />
      <Route path="events/new" element={<OrganiserFormPage />} />
      <Route path="events/:id" element={<OrganiserDetailPage />} />
      <Route path="events/:id/edit" element={<OrganiserFormPage />} />
      <Route path="events/:id/attendees" element={<OrganiserAttendeesPage />} />
      <Route path="analytics" element={<OrganiserAnalyticsPage />} />
    </Routes>
  )
}

function OrganiserPage() {
  const [title, setTitle] = useState('新活动')
  const [city, setCity] = useState('上海')
  const [category, setCategory] = useState('music')
  const [capacity, setCapacity] = useState(50)
  const [price, setPrice] = useState(99)
  const [mine, setMine] = useState<EventVo[]>([])
  const [error, setError] = useState('')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')
  const [dash, setDash] = useState<{ eventCount?: number; sold?: number; sellThrough?: number } | null>(null)
  async function reload() {
    try {
      const page = await api<{ records: EventVo[] }>('GET', `/api/organiser/events?q=${encodeURIComponent(q)}&status=${status}`)
      setMine(page.records ?? [])
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载活动失败')
    }
  }
  useEffect(() => {
    reload()
    api<{ eventCount?: number; sold?: number; sellThrough?: number }>('GET', '/api/organiser/dashboard')
      .then(setDash)
      .catch(() => setDash(null))
  }, [q, status])
  return (
    <div>
      <h1>主办方工作台</h1>
      {dash && (
        <p className="muted">
          活动 {dash.eventCount ?? 0} · 已售 {dash.sold ?? 0} · 售票率 {dash.sellThrough ?? 0}%
        </p>
      )}
      <nav className="row">
        <NavLink to="/organiser">概览</NavLink>
        <NavLink to="/organiser/events">活动</NavLink>
        <NavLink to="/organiser/events/new">新建</NavLink>
        <NavLink to="/organiser/analytics">数据</NavLink>
      </nav>
      {error && <p className="error-text">{error}</p>}
      <form
        className="card"
        onSubmit={async (e) => {
          e.preventDefault()
          const startsAt = new Date(Date.now() + 7 * 86400000).toISOString()
          await api('POST', '/api/organiser/events', {
            title,
            description: '主办方创建的活动',
            category,
            city,
            startsAt,
            endsAt: new Date(Date.now() + 7 * 86400000 + 3 * 3600000).toISOString(),
            priceCents: Math.round(price * 100),
            capacity,
            publish: true,
          })
          await reload()
        }}
      >
        <label htmlFor="org-title">标题</label>
        <input id="org-title" value={title} onChange={(e) => setTitle(e.target.value)} required />
        <label htmlFor="org-city">城市</label>
        <input id="org-city" value={city} onChange={(e) => setCity(e.target.value)} required />
        <label htmlFor="org-cat">分类</label>
        <input id="org-cat" value={category} onChange={(e) => setCategory(e.target.value)} />
        <label htmlFor="org-cap">容量</label>
        <input id="org-cap" type="number" min={1} value={capacity} onChange={(e) => setCapacity(Number(e.target.value))} />
        <label htmlFor="org-price">票价（元）</label>
        <input id="org-price" type="number" min={0} value={price} onChange={(e) => setPrice(Number(e.target.value))} />
        <button type="submit" className="btn-primary">
          发布活动
        </button>
      </form>
      <div className="search-row">
        <input className="search" placeholder="搜索我的活动…" value={q} onChange={(e) => setQ(e.target.value)} />
        <select aria-label="状态筛选" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">全部状态</option>
          <option value="DRAFT">草稿</option>
          <option value="PUBLISHED">已发布</option>
          <option value="ONGOING">进行中</option>
          <option value="FINISHED">已结束</option>
          <option value="CANCELLED">已取消</option>
          <option value="ARCHIVED">已归档</option>
        </select>
      </div>
      {mine.length === 0 ? (
        <EmptyState title="还没有活动" hint="用上面的表单创建第一场活动，也可以先保存草稿。" />
      ) : (
        mine.map((event) => (
          <div key={event.id} className="card booking-row">
            <div>
              <h3>{event.title}</h3>
              <p className="muted">
                {event.status} · {event.city} · 已售 {event.sold}/{event.capacity}
              </p>
            </div>
            <NavLink to={`/organiser/events/${event.id}`} className="btn-secondary">
              查看
            </NavLink>
            <NavLink to={`/organiser/events/${event.id}/attendees`} className="btn-secondary">
              参与者
            </NavLink>
          </div>
        ))
      )}
    </div>
  )
}

function OrganiserEventsPage() {
  const [mine, setMine] = useState<EventVo[]>([])
  const [error, setError] = useState('')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')
  useEffect(() => {
    api<{ records: EventVo[] }>('GET', `/api/organiser/events?q=${encodeURIComponent(q)}&status=${status}`)
      .then((page) => setMine(page.records ?? []))
      .catch((e) => setError(e instanceof ApiError ? e.message : '加载活动失败'))
  }, [q, status])
  return (
    <div>
      <h1>活动管理</h1>
      {error && <p className="error-text">{error}</p>}
      <div className="search-row">
        <input className="search" placeholder="搜索我的活动…" value={q} onChange={(e) => setQ(e.target.value)} />
        <select aria-label="状态筛选" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">全部状态</option>
          <option value="DRAFT">草稿</option>
          <option value="PUBLISHED">已发布</option>
          <option value="ONGOING">进行中</option>
          <option value="FINISHED">已结束</option>
          <option value="CANCELLED">已取消</option>
          <option value="ARCHIVED">已归档</option>
        </select>
        <NavLink to="/organiser/events/new" className="btn-primary">
          新建活动
        </NavLink>
      </div>
      {mine.map((event) => (
        <div key={event.id} className="card booking-row">
          <div>
            <h3>{event.title}</h3>
            <p className="muted">
              {event.status} · {event.city} · 已售 {event.sold}/{event.capacity}
            </p>
          </div>
          <NavLink to={`/organiser/events/${event.id}`}>查看</NavLink>
          <NavLink to={`/organiser/events/${event.id}/edit`}>编辑</NavLink>
        </div>
      ))}
    </div>
  )
}

function OrganiserFormPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [title, setTitle] = useState('新活动')
  const [summary, setSummary] = useState('')
  const [description, setDescription] = useState('主办方创建的活动')
  const [city, setCity] = useState('上海')
  const [category, setCategory] = useState('music')
  const [capacity, setCapacity] = useState(50)
  const [price, setPrice] = useState(99)
  const [venueName, setVenueName] = useState('')
  const [error, setError] = useState('')
  const [coverUrl, setCoverUrl] = useState('')
  async function submit(publish: boolean) {
    if (capacity <= 0) {
      setError('容量必须大于零')
      return
    }
    const startsAt = new Date(Date.now() + 7 * 86400000).toISOString()
    const body = {
      title,
      summary,
      description,
      category,
      city,
      venueName,
      coverUrl: coverUrl || null,
      startsAt,
      endsAt: new Date(Date.now() + 7 * 86400000 + 3 * 3600000).toISOString(),
      priceCents: Math.round(price * 100),
      capacity,
      publish,
    }
    try {
      if (id) await api('PUT', `/api/organiser/events/${id}`, body)
      else await api('POST', '/api/organiser/events', body)
      navigate('/organiser/events')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '保存失败')
    }
  }
  return (
    <form
      className="card"
      onSubmit={(e) => {
        e.preventDefault()
        submit(false)
      }}
    >
      <h1>{id ? '编辑活动' : '新建活动'}</h1>
      {error && <p className="error-text">{error}</p>}
      <label htmlFor="f-title">标题</label>
      <input id="f-title" value={title} onChange={(e) => setTitle(e.target.value)} required />
      <label htmlFor="f-summary">摘要</label>
      <input id="f-summary" value={summary} onChange={(e) => setSummary(e.target.value)} />
      <label htmlFor="f-desc">介绍</label>
      <textarea id="f-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
      <label htmlFor="f-city">城市</label>
      <input id="f-city" value={city} onChange={(e) => setCity(e.target.value)} required />
      <label htmlFor="f-venue">场地</label>
      <input id="f-venue" value={venueName} onChange={(e) => setVenueName(e.target.value)} />
      <label htmlFor="f-cat">分类</label>
      <input id="f-cat" value={category} onChange={(e) => setCategory(e.target.value)} />
      <label htmlFor="f-cap">容量</label>
      <input id="f-cap" type="number" min={1} value={capacity} onChange={(e) => setCapacity(Number(e.target.value))} />
      <label htmlFor="f-price">票价（元）</label>
      <input id="f-price" type="number" min={0} value={price} onChange={(e) => setPrice(Number(e.target.value))} />
      <label htmlFor="f-cover">封面</label>
      <input
        id="f-cover"
        type="file"
        accept="image/*"
        onChange={async (e) => {
          const file = e.target.files?.[0]
          if (!file) return
          const { uploadFile } = await import('./api')
          const asset = await uploadFile<{ id: number; publicUrl: string }>('/api/media/images', file)
          setCoverUrl(asset.publicUrl)
        }}
      />
      <button type="submit" className="btn-secondary">
        保存草稿
      </button>
      <button type="button" className="btn-primary" onClick={() => submit(true)}>
        发布活动
      </button>
    </form>
  )
}

function OrganiserDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [event, setEvent] = useState<EventVo | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    api<EventVo>('GET', `/api/organiser/events/${id}`).then(setEvent).catch((e) => setError(e instanceof ApiError ? e.message : '加载失败'))
  }, [id])
  if (error) return <EmptyState title={error} hint="只能查看自己的活动。" />
  if (!event) return <p className="muted">加载中…</p>
  return (
    <article className="card">
      <p className="muted">草稿 → 已发布/售票中 → 进行中 → 已结束 → 已归档</p>
      <h1>{event.title}</h1>
      <p className="muted">
        {event.status} · 已售 {event.sold}/{event.capacity}
      </p>
      <div className="row">
        {event.status === 'DRAFT' && (
          <button className="btn-primary" onClick={() => api('POST', `/api/organiser/events/${event.id}/publish`).then(() => navigate('/organiser/events'))}>
            发布
          </button>
        )}
        {(event.status === 'PUBLISHED' || event.status === 'ONGOING') && (
          <button
            className="btn-secondary"
            onClick={() => {
              const reason = window.prompt('取消原因（将通知所有已预订用户）')
              if (!reason) return
              api('POST', `/api/organiser/events/${event.id}/cancel`, { reason }).then(() => navigate('/organiser/events'))
            }}
          >
            取消活动
          </button>
        )}
        {(event.status === 'FINISHED' || event.status === 'CANCELLED') && (
          <button className="btn-secondary" onClick={() => api('POST', `/api/organiser/events/${event.id}/archive`, { note: '归档' }).then(() => navigate('/organiser/events'))}>
            归档
          </button>
        )}
        {event.status === 'DRAFT' && (
          <button
            className="btn-secondary"
            onClick={() => {
              if (window.confirm('确定删除这份草稿？此操作不可恢复。')) {
                api('DELETE', `/api/organiser/events/${event.id}`).then(() => navigate('/organiser/events'))
              }
            }}
          >
            删除草稿
          </button>
        )}
        <button className="btn-secondary" onClick={() => api('POST', `/api/organiser/events/${event.id}/duplicate`).then(() => navigate('/organiser/events'))}>
          复制
        </button>
        <NavLink to={`/organiser/events/${event.id}/edit`} className="btn-secondary">
          编辑
        </NavLink>
        <NavLink to={`/organiser/events/${event.id}/attendees`} className="btn-secondary">
          参与者
        </NavLink>
      </div>
    </article>
  )
}

function OrganiserAttendeesPage() {
  const { id } = useParams()
  const [rows, setRows] = useState<{ bookingId: number; ticketId: number; name: string; email: string; status: string }[]>([])
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  useEffect(() => {
    api<typeof rows>('GET', `/api/organiser/events/${id}/attendees`).then(setRows).catch((e) => setError(e instanceof ApiError ? e.message : '加载失败'))
  }, [id])
  return (
    <div>
      <h1>参与者管理</h1>
      {error && <p className="error-text">{error}</p>}
      <form
        className="card"
        onSubmit={async (e) => {
          e.preventDefault()
          try {
            await api('POST', '/api/organiser/tickets/check-in', { code, source: 'manual' })
            const next = await api<typeof rows>('GET', `/api/organiser/events/${id}/attendees`)
            setRows(next)
          } catch (err) {
            setError(err instanceof ApiError ? err.message : '核销失败')
          }
        }}
      >
        <label htmlFor="check-code">票码核销</label>
        <input id="check-code" value={code} onChange={(e) => setCode(e.target.value)} />
        <button type="submit" className="btn-primary">
          签到
        </button>
      </form>
      <a className="btn-secondary" href={`/api/organiser/events/${id}/attendees.csv`}>
        导出 CSV
      </a>
      {rows.map((row) => (
        <div key={row.ticketId} className="card booking-row">
          <div>
            <h3>{row.name || row.email}</h3>
            <p className="muted">
              订单 {row.bookingId} · 票 {row.ticketId} · {row.status}
            </p>
          </div>
        </div>
      ))}
    </div>
  )
}

function OrganiserAnalyticsPage() {
  const [data, setData] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    api<Record<string, unknown>>('GET', '/api/organiser/analytics').then(setData).catch((e) => setError(e instanceof ApiError ? e.message : '加载失败'))
  }, [])
  return (
    <div>
      <h1>数据分析</h1>
      {error && <p className="error-text">{error}</p>}
      {data && (
        <div className="card">
          <p>浏览 {String(data.views ?? 0)}</p>
          <p>点击 {String(data.clicks ?? 0)}</p>
          <p>预订 {String(data.bookings ?? 0)}</p>
          <p>转化 {String(data.conversion ?? 0)}%</p>
        </div>
      )}
    </div>
  )
}

export default function App() {
  const { user, ready } = useAuth()
  if (!ready) return <div className="container">加载中…</div>
  return (
    <>
      <TopBar />
      <div className="container">
        <Routes>
          <Route path="/" element={<EventsPage />} />
          <Route path="/events/:id" element={<EventDetailPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/bookings" element={user ? <BookingsPage /> : <Navigate to="/login" replace />} />
          <Route path="/bookings/:id" element={user ? <BookingDetailPage /> : <Navigate to="/login" replace />} />
          <Route path="/favourites" element={user ? <FavouritesPage /> : <Navigate to="/login" replace />} />
          <Route path="/notifications" element={user ? <NotificationsPage /> : <Navigate to="/login" replace />} />
          <Route path="/organiser/*" element={user ? <OrganiserGate /> : <Navigate to="/login" replace />} />
        </Routes>
      </div>
    </>
  )
}
