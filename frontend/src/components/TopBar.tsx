import { useState } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'

const NAV_LINKS = [
  { to: '/', label: '活动', end: true, auth: false },
  { to: '/bookings', label: '我的预订', end: false, auth: true },
  { to: '/favourites', label: '收藏', end: false, auth: true },
  { to: '/notifications', label: '消息', end: false, auth: true },
]

function initials(name?: string, email?: string) {
  const source = name?.trim() || email?.trim() || '?'
  return source.slice(0, 1).toUpperCase()
}

export function TopBar() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)
  const links = NAV_LINKS.filter((link) => !link.auth || user)

  return (
    <header className="topbar">
      <div className="topbar-inner">
        <NavLink to="/" className="brand" onClick={() => setMenuOpen(false)}>
          Event<span>Pulse</span>
        </NavLink>

        <button
          type="button"
          className="nav-toggle"
          aria-expanded={menuOpen}
          aria-label={menuOpen ? '收起导航' : '展开导航'}
          onClick={() => setMenuOpen((open) => !open)}
        >
          <span aria-hidden />
        </button>

        <nav className={menuOpen ? 'open' : ''} onClick={() => setMenuOpen(false)}>
          {links.map((link) => (
            <NavLink key={link.to} to={link.to} end={link.end}>
              {link.label}
            </NavLink>
          ))}
          {user?.role === 'ORGANISER' && <NavLink to="/organiser">工作台</NavLink>}
        </nav>

        {user ? (
          <span className="row user-box">
            <span className="avatar" aria-hidden>
              {initials(user.name, user.email)}
            </span>
            <span className="muted hide-sm">{user.email}</span>
            <button
              className="btn-secondary btn-sm"
              onClick={() => {
                logout()
                navigate('/')
              }}
            >
              退出
            </button>
          </span>
        ) : (
          <NavLink to="/login" className="btn-ghost btn-sm">
            登录 / 注册
          </NavLink>
        )}
      </div>
    </header>
  )
}
