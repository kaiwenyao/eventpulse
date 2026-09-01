import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { AuthProvider, SessionUser } from '../auth'

const apiMock = vi.hoisted(() => ({ fn: vi.fn(), token: null as string | null }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: apiMock.fn, getAccessToken: () => apiMock.token }
})

const user: SessionUser = { id: 1, email: 'u@t.dev', name: '阿达', role: 'USER' }

function renderApp(route = '/') {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  apiMock.fn.mockReset()
  apiMock.token = null
})

describe('TopBar', () => {
  it('hides member links from signed-out visitors', async () => {
    apiMock.fn.mockResolvedValue([])
    renderApp()
    await waitFor(() => expect(screen.getByRole('link', { name: '活动' })).toBeInTheDocument())
    expect(screen.queryByRole('link', { name: '我的预订' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '登录 / 注册' })).toBeInTheDocument()
  })

  it('shows the console link only for organisers', async () => {
    apiMock.token = 'tok'
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve({ ...user, role: 'ORGANISER' })
      return Promise.resolve([])
    })
    renderApp()
    await waitFor(() => expect(screen.getByRole('link', { name: '工作台' })).toBeInTheDocument())
  })

  it('toggles the mobile navigation and reports its expanded state', async () => {
    apiMock.fn.mockResolvedValue([])
    renderApp()
    const toggle = await screen.findByRole('button', { name: '展开导航' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    await userEvent.click(toggle)
    expect(await screen.findByRole('button', { name: '收起导航' })).toHaveAttribute('aria-expanded', 'true')
  })

  it('signs the user out and returns to discovery', async () => {
    apiMock.token = 'tok'
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      return Promise.resolve([])
    })
    renderApp('/bookings')
    await waitFor(() => expect(screen.getByRole('button', { name: '退出' })).toBeInTheDocument())
    // The avatar falls back to the display name's first character.
    expect(screen.getByText('阿')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '退出' }))
    await waitFor(() => expect(screen.getByRole('link', { name: '登录 / 注册' })).toBeInTheDocument())
  })

  it('toggles the colour theme and flips its accessible label', async () => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')

    apiMock.fn.mockResolvedValue([])
    renderApp()
    // No attribute → treated as dark, so the button offers the light switch.
    const toggle = await screen.findByRole('button', { name: '切换到浅色主题' })
    expect(document.documentElement.dataset.theme).toBeUndefined()

    await userEvent.click(toggle)
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(localStorage.getItem('theme')).toBe('light')
    expect(await screen.findByRole('button', { name: '切换到深色主题' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '切换到深色主题' }))
    expect(document.documentElement.dataset.theme).toBe('dark')
  })
})
