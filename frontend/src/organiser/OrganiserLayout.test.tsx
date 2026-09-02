import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { AuthProvider, SessionUser } from '../auth'

const apiMock = vi.hoisted(() => ({ fn: vi.fn(), token: null as string | null }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: apiMock.fn, getAccessToken: () => apiMock.token }
})

const organiser: SessionUser = { id: 2, email: 'o@t.dev', name: '主办', role: 'ORGANISER' }

function renderApp(route: string) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

function railLinks() {
  // The console rail is the <nav> under the "主办方控制台" brand; keep the
  // assertion scoped there so page-internal links (breadcrumbs etc.) can't
  // pollute the count.
  const rail = screen.getByText('主办方控制台').closest('aside')!
  return Array.from(rail.querySelectorAll<HTMLAnchorElement>('a'))
}

/** Every route an organiser console page can render must light exactly one rail tab. */
const ROUTE_TO_TAB: Array<[string, string]> = [
  ['/organiser', '概览'],
  ['/organiser/events', '活动管理'],
  ['/organiser/events/9', '活动管理'],
  ['/organiser/events/9/edit', '活动管理'],
  ['/organiser/events/9/attendees', '活动管理'],
  // Regression: /organiser/events/new is a sibling of /organiser/events and the
  // NavLink prefix match used to light BOTH 活动管理 and 新建活动 at once.
  ['/organiser/events/new', '新建活动'],
  ['/organiser/analytics', '数据分析'],
]

describe('OrganiserLayout console rail active state', () => {
  beforeEach(() => {
    apiMock.fn.mockReset()
    apiMock.token = 'tok'
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      return Promise.resolve([])
    })
  })

  for (const [route, expectedTab] of ROUTE_TO_TAB) {
    it(`lights only ${expectedTab} on ${route}`, async () => {
      renderApp(route)
      await waitFor(() => expect(screen.getByText('主办方控制台')).toBeInTheDocument())
      await waitFor(() => expect(railLinks().filter((a) => a.classList.contains('active')).length).toBeGreaterThan(0))

      const active = railLinks().filter((a) => a.classList.contains('active'))
      expect(active).toHaveLength(1)
      expect(active[0].textContent).toContain(expectedTab)
    })
  }
})
