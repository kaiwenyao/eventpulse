import { FormEvent, useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, formatMoney, formatTime } from './api'
import { useAuth } from './auth'

interface EventVo {
  id: number
  title: string
  description: string
  category: string
  city: string
  startsAt: string
  priceCents: number
  capacity: number
  sold: number
  remaining: number
  status: string
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
  bookingId: number
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
        {user && <NavLink to="/notifications">消息</NavLink>}
        {user?.role === 'ORGANISER' && <NavLink to="/organiser">主办方</NavLink>}
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
  useEffect(() => {
    const params = new URLSearchParams()
    if (q) params.set('q', q)
    if (cat) params.set('category', cat)
    api<EventVo[]>('GET', `/api/events${params.size ? `?${params}` : ''}`)
      .then(setEvents)
      .catch(() => setEvents([]))
  }, [q, cat])
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
  const navigate = useNavigate()
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
      <div className="detail-side">
        <div className="detail-price">{event.priceCents === 0 ? '免费' : formatMoney(event.priceCents)}</div>
        <SoldBar sold={event.sold} capacity={event.capacity} />
        {user ? (
          <button
            onClick={async () => {
              try {
                await api('POST', '/api/bookings', { eventId: event.id, quantity: 1 })
                navigate('/bookings')
              } catch (e) {
                setError(e instanceof ApiError ? e.message : '预订失败')
              }
            }}
          >
            预订 1 张
          </button>
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
              <h3>{b.eventTitle}</h3>
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
      <h1>Kafka 消息</h1>
      <p className="muted">预订成功后，消费者会往这里写一条通知。</p>
      {items.length === 0 ? (
        <EmptyState title="还没有消息" hint="预订一场活动后，Kafka 会把通知送到这里。" />
      ) : (
        items.map((n) => (
          <div key={n.id} className="card notification-row">
            <span className="notification-dot" aria-hidden />
            <div>
              <p>{n.message}</p>
              <p className="muted small">{formatTime(n.createdAt)}</p>
            </div>
          </div>
        ))
      )}
    </div>
  )
}

function OrganiserPage() {
  const [title, setTitle] = useState('新活动')
  const [city, setCity] = useState('上海')
  const [mine, setMine] = useState<EventVo[]>([])
  async function reload() {
    setMine(await api<EventVo[]>('GET', '/api/events/mine'))
  }
  useEffect(() => {
    reload()
  }, [])
  return (
    <div>
      <h1>主办方</h1>
      <form
        className="card"
        onSubmit={async (e) => {
          e.preventDefault()
          await api('POST', '/api/events', {
            title,
            description: '主办方创建的活动',
            category: 'music',
            city,
            startsAt: new Date(Date.now() + 7 * 86400000).toISOString(),
            priceCents: 9900,
            capacity: 50,
          })
          await reload()
        }}
      >
        <label htmlFor="org-title">标题</label>
        <input id="org-title" value={title} onChange={(e) => setTitle(e.target.value)} />
        <label htmlFor="org-city">城市</label>
        <input id="org-city" value={city} onChange={(e) => setCity(e.target.value)} />
        <button type="submit" className="btn-primary">
          发布
        </button>
      </form>
      {mine.length === 0 ? (
        <EmptyState title="还没有发布过活动" hint="用上面的表单发布第一场活动。" />
      ) : (
        mine.map((event) => (
          <div key={event.id} className="card booking-row">
            <div>
              <h3>{event.title}</h3>
              <p className="muted">{event.status}</p>
            </div>
          </div>
        ))
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
          <Route path="/notifications" element={user ? <NotificationsPage /> : <Navigate to="/login" replace />} />
          <Route path="/organiser" element={user ? <OrganiserPage /> : <Navigate to="/login" replace />} />
        </Routes>
      </div>
    </>
  )
}
