import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth'
import { ChartIcon, GridIcon, PlusIcon, TicketIcon } from '../ui/Icons'

const CONSOLE_NAV = [
  { to: '/organiser', label: '概览', end: true, Icon: GridIcon },
  { to: '/organiser/events', label: '活动管理', end: false, Icon: TicketIcon },
  { to: '/organiser/events/new', label: '新建活动', end: true, Icon: PlusIcon },
  { to: '/organiser/analytics', label: '数据分析', end: false, Icon: ChartIcon },
]

/**
 * Two-pane operator shell: a persistent rail on the left, work surface on the
 * right. On narrow screens the rail collapses into a horizontal scroller so the
 * console stays usable on a phone at the venue door.
 */
export function OrganiserLayout() {
  const { user } = useAuth()
  const { pathname } = useLocation()
  const onNew = pathname === '/organiser/events/new'
  return (
    <div className="console">
      <aside className="console-rail">
        <p className="console-brand">主办方控制台</p>
        <nav className="console-nav">
          {CONSOLE_NAV.map(({ to, label, end, Icon }) => {
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
                <span>{label}</span>
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
              <strong>{user.name || '主办方'}</strong>
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
