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
        <span className="row">
          <span className="muted">{user.email}</span>
          <button
            className="secondary"
            onClick={() => {
              logout()
              navigate('/')
            }}
          >
            退出
          </button>
        </span>
      ) : (
        <NavLink to="/login">登录 / 注册</NavLink>
      )}
    </header>
  )
}

function EventsPage() {
  const [events, setEvents] = useState<EventVo[]>([])
  const [q, setQ] = useState('')
  useEffect(() => {
    api<EventVo[]>('GET', `/api/events${q ? `?q=${encodeURIComponent(q)}` : ''}`).then(setEvents).catch(() => setEvents([]))
  }, [q])
  return (
    <div>
      <h1>发现活动</h1>
      <input placeholder="搜索活动…" value={q} onChange={(e) => setQ(e.target.value)} />
      <div className="grid">
        {events.map((event) => (
          <NavLink key={event.id} to={`/events/${event.id}`} className="event-card">
            <div className="title">{event.title}</div>
            <div className="meta">
              {event.city} · {event.category} · {formatMoney(event.priceCents)}
            </div>
            <div className="muted">余票 {event.remaining}</div>
          </NavLink>
        ))}
      </div>
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
  if (error) return <p className="muted">{error}</p>
  if (!event) return <p>加载中…</p>
  return (
    <div className="card">
      <h1>{event.title}</h1>
      <p className="muted">
        {event.city} · {formatTime(event.startsAt)} · {formatMoney(event.priceCents)}
      </p>
      <p>{event.description}</p>
      <p>余票 {event.remaining}</p>
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
        <NavLink to="/login">登录后预订</NavLink>
      )}
    </div>
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
    <form className="card" onSubmit={onSubmit}>
      <h1>{mode === 'login' ? '登录' : '注册'}</h1>
      <label>邮箱</label>
      <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      <label>密码</label>
      <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      {mode === 'register' && (
        <>
          <label>昵称</label>
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </>
      )}
      {error && <p className="muted">{error}</p>}
      <button type="submit">{mode === 'login' ? '登录' : '注册'}</button>
      <button type="button" className="secondary" onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
        {mode === 'login' ? '去注册' : '去登录'}
      </button>
      <p className="muted">演示账号 user@eventpulse.dev / User123456 ，主办方 organiser@eventpulse.dev / Organiser123456</p>
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
      {items.map((b) => (
        <div key={b.id} className="card">
          <h3>{b.eventTitle}</h3>
          <p>
            {b.quantity} 张 · {b.status}
          </p>
          {b.status === 'CONFIRMED' && (
            <button
              className="secondary"
              onClick={async () => {
                const updated = await api<BookingVo>('POST', `/api/bookings/${b.id}/cancel`)
                setItems((prev) => prev.map((x) => (x.id === b.id ? updated : x)))
              }}
            >
              取消
            </button>
          )}
        </div>
      ))}
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
      {items.map((n) => (
        <div key={n.id} className="card">
          {n.message}
        </div>
      ))}
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
        <label>标题</label>
        <input value={title} onChange={(e) => setTitle(e.target.value)} />
        <label>城市</label>
        <input value={city} onChange={(e) => setCity(e.target.value)} />
        <button type="submit">发布</button>
      </form>
      {mine.map((event) => (
        <div key={event.id} className="card">
          {event.title} · {event.status}
        </div>
      ))}
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
