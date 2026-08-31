import { ReactNode } from 'react'
import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import App from './App'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, useAuth, SessionUser } from './auth'

// Replace ONLY the network edge: the real module keeps setAccessToken state,
// ApiError and formatters; `api` becomes a per-test stub. refreshToken() calls
// the stubbed api('POST', '/api/v1/auth/refresh', {}), so rotate-on-mount and
// login flows are observable without a server.
const apiMock = vi.hoisted(() => ({ fn: vi.fn(), token: null as string | null }))
vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>()
  return {
    ...actual,
    api: apiMock.fn,
    getAccessToken: () => apiMock.token,
  }
})

import { ApiError } from './api'

function wrapper({ children }: { children: ReactNode }) {
  return <AuthProvider>{children}</AuthProvider>
}

const user: SessionUser = { id: 1, email: 'u@t.dev', name: 'U', role: 'USER' }

beforeEach(() => {
  apiMock.fn.mockReset()
  apiMock.token = null
  apiMock.fn.mockRejectedValue(new ApiError(500, 'network down'))
})

describe('AuthProvider session restore', () => {
  it('rotates the refresh cookie on mount and keeps the user', async () => {
    apiMock.fn.mockResolvedValueOnce({ accessToken: 'at-1', user })
    const { result } = renderHook(() => useAuth(), { wrapper })

    await waitFor(() => expect(result.current.ready).toBe(true))
    expect(result.current.user).toBeNull()
  })

  it('clears the session when the cookie is missing or failed, but still becomes ready', async () => {
    apiMock.fn.mockRejectedValueOnce(new ApiError(401, 'missing'))
    const { result } = renderHook(() => useAuth(), { wrapper })

    await waitFor(() => expect(result.current.ready).toBe(true))
    expect(result.current.user).toBeNull()
  })
})

describe('AuthProvider login / register / logout', () => {
  it('login stores the access token and user', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/auth/login') {
        return Promise.resolve({ token: 'at', user })
      }
      return Promise.resolve({})
    })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    await act(async () => { await result.current.login('u@t.dev', 'pw-123456789') })
    expect(result.current.user?.email).toBe('u@t.dev')
  })

  it('failed login surfaces the error and keeps the user empty', async () => {
    apiMock.fn.mockRejectedValue(new ApiError(401, 'invalid credentials'))
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    await expect(result.current.login('u@t.dev', 'wrong-password')).rejects.toThrow('invalid credentials')
    expect(result.current.user).toBeNull()
  })

  it('register uses the register endpoint with the display name', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/auth/register') {
        return Promise.resolve({ token: 'at', user })
      }
      return Promise.resolve({})
    })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    await act(async () => { await result.current.register('n@t.dev', 'pw-123456789', 'Tester') })
    expect(result.current.user?.id).toBe(1)
    expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/auth/register',
      expect.objectContaining({ email: 'n@t.dev', name: 'Tester' }))
  })

  it('logout clears the user even when the server call fails', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/auth/login') {
        return Promise.resolve({ token: 'at', user })
      }
      return Promise.reject(new ApiError(500, 'boom'))
    })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    await act(async () => { await result.current.login('u@t.dev', 'pw-123456789') })
    await act(async () => { result.current.logout() })
    expect(result.current.user).toBeNull()
  })
})

describe('App pages', () => {
  it('renders the events search box', async () => {
    apiMock.fn.mockResolvedValue([])
    render(
      <MemoryRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByPlaceholderText('搜索活动…')).toBeInTheDocument())
  })

  it('toggles login to register', async () => {
    apiMock.fn.mockResolvedValue([])
    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument())
    await userEvent.click(screen.getByText('去注册'))
    expect(screen.getByRole('heading', { name: '注册' })).toBeInTheDocument()
  })

  it('renders event cards and event detail', async () => {
    const event = {
      id: 1,
      title: '摇滚夜',
      description: 'd',
      category: 'music',
      city: '上海',
      startsAt: '2026-09-10T12:00:00Z',
      priceCents: 18000,
      capacity: 10,
      sold: 1,
      remaining: 9,
      status: 'PUBLISHED',
    }
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path.startsWith('/api/events/1')) return Promise.resolve(event)
      if (path.startsWith('/api/events')) return Promise.resolve([event])
      return Promise.resolve([])
    })
    render(
      <MemoryRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText('摇滚夜')).toBeInTheDocument())
  })

  it('renders event detail for guests', async () => {
    apiMock.fn.mockResolvedValue({
      id: 1,
      title: '摇滚夜',
      description: 'd',
      category: 'music',
      city: '上海',
      startsAt: '2026-09-10T12:00:00Z',
      priceCents: 18000,
      capacity: 10,
      sold: 1,
      remaining: 9,
      status: 'PUBLISHED',
    })
    render(
      <MemoryRouter initialEntries={['/events/1']}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByText('登录后预订')).toBeInTheDocument())
  })

  it('logs in and visits bookings and notifications', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/login') return Promise.resolve({ token: 't', user })
      if (path === '/api/bookings') {
        return Promise.resolve([
          { id: 1, eventId: 1, eventTitle: '摇滚夜', quantity: 1, status: 'CONFIRMED', createdAt: '2026-09-01T00:00:00Z' },
        ])
      }
      if (path === '/api/notifications') {
        return Promise.resolve([{ id: 1, bookingId: 1, message: 'Kafka 已处理：BOOKING_CREATED', createdAt: '2026-09-01T00:00:00Z' }])
      }
      if (path.startsWith('/api/events')) return Promise.resolve([])
      if (path.includes('/cancel')) {
        return Promise.resolve({
          id: 1,
          eventId: 1,
          eventTitle: '摇滚夜',
          quantity: 1,
          status: 'CANCELLED',
          createdAt: '2026-09-01T00:00:00Z',
        })
      }
      return Promise.resolve([])
    })
    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument())
    await userEvent.type(document.querySelector('input[type="email"]') as HTMLInputElement, 'u@t.dev')
    await userEvent.type(document.querySelector('input[type="password"]') as HTMLInputElement, 'pw')
    await userEvent.click(screen.getByRole('button', { name: '登录' }))
    await waitFor(() => expect(screen.getByText('我的预订')).toBeInTheDocument())
    await userEvent.click(screen.getByText('我的预订'))
    await waitFor(() => expect(screen.getByText('摇滚夜')).toBeInTheDocument())
    await userEvent.click(screen.getByText('取消'))
    await userEvent.click(screen.getByText('消息'))
    await waitFor(() => expect(screen.getByText('Kafka 已处理：BOOKING_CREATED')).toBeInTheDocument())
  })

  it('restores a stored session and opens organiser page', async () => {
    apiMock.token = 'stored'
    const organiser = { ...user, role: 'ORGANISER' }
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path === '/api/events/mine' || path === '/api/events') return Promise.resolve([])
      if (path === '/api/events') return Promise.resolve([])
      return Promise.resolve([])
    })
    render(
      <MemoryRouter initialEntries={['/organiser']}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByRole('heading', { name: '主办方' })).toBeInTheDocument())
    await userEvent.type(screen.getAllByRole('textbox')[0], '夜')
    await userEvent.click(screen.getByRole('button', { name: '发布' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/events', expect.anything()))
  })
})