import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth'
import { ChartIcon, GridIcon, PlusIcon, TicketIcon } from '../ui/Icons'

const CONSOLE_NAV = [
  { to: '/organiser', key: 'organiser.overview', end: true, Icon: GridIcon },
  { to: '/organiser/events', key: 'organiser.events', end: false, Icon: TicketIcon },
  { to: '/organiser/events/new', key: 'organiser.newEvent', end: true, Icon: PlusIcon },
  { to: '/organiser/analytics', key: 'organiser.analytics', end: false, Icon: ChartIcon },
] as const

/**
 * Two-pane operator shell: a persistent rail on the left, work surface on the
 * right. On narrow screens the rail collapses into a horizontal scroller so the
 * console stays usable on a phone at the venue door.
 */
export function OrganiserLayout() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const { pathname } = useLocation()
  const onNew = pathname === '/organiser/events/new'
  return (
    <div className="console">
      <aside className="console-rail">
        <p className="console-brand">{t('organiser.brand')}</p>
        <nav className="console-nav">
          {CONSOLE_NAV.map(({ to, key, end, Icon }) => {
            // 前缀匹配会让 /organiser/events/new 同时命中「活动管理」和「新建活动」，
            // 所以在新建页掐掉「活动管理」的高亮，保证同一时刻只有一个 tab 亮。
            const override = to === '/organiser/events' ? !onNew : undefined
            return (
              <NavLink
                key={to}
                to={to}
                end={end}
                className={({ isActive }) => (override === false || !isActive ? '' : 'active')}
              >
                <Icon />
                <span>{t(key)}</span>
              </NavLink>
            )
          })}
        </nav>
        {user && (
          <div className="console-user">
            <span className="avatar" aria-hidden>
              {(user.name || user.email || '?').slice(0, 1).toUpperCase()}
            </span>
            <span className="console-user-copy">
              <strong>{user.name || t('organiser.fallbackName')}</strong>
              <span className="muted small">{user.email}</span>
            </span>
          </div>
        )}
      </aside>
      <main className="console-main">
        <Outlet />
      </main>
    </div>
  )
}
