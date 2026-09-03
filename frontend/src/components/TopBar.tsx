import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'
import { changeLocale, currentLocale } from '../i18n'
import { currentTheme, toggleTheme, type Theme } from '../theme'
import { MoonIcon, SunIcon } from '../ui/Icons'

const NAV_LINKS = [
  { to: '/', key: 'nav.events', end: true, auth: false },
  { to: '/bookings', key: 'nav.bookings', end: false, auth: true },
  { to: '/cart', key: 'nav.cart', end: false, auth: true },
  { to: '/favourites', key: 'nav.favourites', end: false, auth: true },
  { to: '/notifications', key: 'nav.notifications', end: false, auth: true },
  { to: '/profile', key: 'nav.profile', end: false, auth: true },
] as const

function initials(name?: string, email?: string) {
  const source = name?.trim() || email?.trim() || '?'
  return source.slice(0, 1).toUpperCase()
}

export function TopBar() {
  const { t } = useTranslation()
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [menuOpen, setMenuOpen] = useState(false)
  const [theme, setTheme] = useState<Theme>(() => currentTheme())
  const locale = currentLocale()
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
          aria-label={menuOpen ? t('nav.collapse') : t('nav.expand')}
          onClick={() => setMenuOpen((open) => !open)}
        >
          <span aria-hidden />
        </button>

        <nav className={menuOpen ? 'open' : ''} onClick={() => setMenuOpen(false)}>
          {links.map((link) => (
            <NavLink key={link.to} to={link.to} end={link.end}>
              {t(link.key)}
            </NavLink>
          ))}
          {user?.role === 'ORGANISER' && <NavLink to="/organiser">{t('nav.console')}</NavLink>}
        </nav>

        <button
          type="button"
          className="theme-toggle"
          onClick={() => setTheme(toggleTheme())}
          aria-label={theme === 'dark' ? t('nav.themeToLight') : t('nav.themeToDark')}
          title={theme === 'dark' ? t('nav.themeToLight') : t('nav.themeToDark')}
        >
          {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
        </button>

        <button
          type="button"
          className="theme-toggle lang-toggle"
          onClick={() => void changeLocale(locale === 'zh' ? 'en' : 'zh')}
          aria-label={locale === 'zh' ? t('nav.switchToEn') : t('nav.switchToZh')}
          title={locale === 'zh' ? t('nav.switchToEn') : t('nav.switchToZh')}
        >
          {locale === 'zh' ? 'EN' : '中'}
        </button>

        {user ? (
          <span className="row user-box">
            <NavLink to="/profile" className="avatar" aria-label={t('nav.profile')} title={t('nav.profile')}>
              {initials(user.name, user.email)}
            </NavLink>
            <span className="muted hide-sm">{user.email}</span>
            <button
              className="btn-secondary btn-sm"
              onClick={() => {
                logout()
                navigate('/')
              }}
            >
              {t('nav.logout')}
            </button>
          </span>
        ) : (
          <NavLink to="/login" className="btn-ghost btn-sm">
            {t('nav.loginRegister')}
          </NavLink>
        )}
      </div>
    </header>
  )
}
