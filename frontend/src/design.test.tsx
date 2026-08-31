import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import App from './App'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, SessionUser } from './auth'

const apiMock = vi.hoisted(() => ({ fn: vi.fn(), token: null as string | null }))
vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>()
  return { ...actual, api: apiMock.fn, getAccessToken: () => apiMock.token }
})

const user: SessionUser = { id: 1, email: 'u@t.dev', name: 'U', role: 'USER' }

function renderApp(route = '/') {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

const event = {
  id: 1,
  title: '城市脉搏 · 独立摇滚之夜',
  description: 'd',
  category: 'music',
  city: '上海',
  startsAt: '2026-09-10T12:00:00Z',
  priceCents: 18000,
  capacity: 10,
  sold: 9,
  remaining: 1,
  status: 'PUBLISHED',
}

beforeEach(() => {
  apiMock.fn.mockReset()
  apiMock.token = null
})

describe('discovery page design elements', () => {
  it('renders category pill and sold-ratio meter on event tickets', async () => {
    apiMock.fn.mockResolvedValue([event])
    renderApp()
    await waitFor(() => expect(screen.getByText('城市脉搏 · 独立摇滚之夜')).toBeInTheDocument())
    // Category pill, city label and sold meter label render on the stub.
    expect(document.querySelector('.pill-music')).not.toBeNull()
    expect(screen.getAllByText('上海').length).toBeGreaterThan(0)
    expect(screen.getByText('1 张余票')).toBeInTheDocument()
    expect(document.querySelector('.sold-fill')).not.toBeNull()
  })

  it('filters by category through the chip bar', async () => {
    apiMock.fn.mockResolvedValue([])
    renderApp()
    await waitFor(() => expect(screen.getByRole('button', { name: '音乐' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '音乐' }))
    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith('GET', expect.stringContaining('category=music')),
    )
  })

  it('shows an empty state when there are no events', async () => {
    apiMock.fn.mockResolvedValue([])
    renderApp()
    await waitFor(() => expect(screen.getByText('还没有活动')).toBeInTheDocument())
    expect(screen.getByText(/换个关键词或分类试试/)).toBeInTheDocument()
  })

  it('shows an empty state when the search matches nothing', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path.includes('q=')) return Promise.resolve([])
      return Promise.resolve([event])
    })
    renderApp()
    await waitFor(() => expect(screen.getByText('城市脉搏 · 独立摇滚之夜')).toBeInTheDocument())
    await userEvent.type(screen.getByPlaceholderText('搜索活动…'), 'zzz')
    await waitFor(() => expect(screen.getByText('还没有活动')).toBeInTheDocument())
  })
})

describe('event detail', () => {
  it('renders price, remaining tickets and the booking CTA', async () => {
    apiMock.fn.mockResolvedValue(event)
    renderApp('/events/1')
    await waitFor(() => expect(screen.getByText('城市脉搏 · 独立摇滚之夜')).toBeInTheDocument())
    expect(screen.getByText('¥180.00')).toBeInTheDocument()
    expect(screen.getByText('1 张余票')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '登录后预订' })).toBeInTheDocument()
  })

  it('shows an empty state when the event is missing', async () => {
    apiMock.fn.mockRejectedValue(new Error('404'))
    renderApp('/events/999')
    await waitFor(() => expect(screen.getByText('活动不存在')).toBeInTheDocument())
  })
})

describe('list pages (signed in)', () => {
  function renderSignedIn(route: string) {
    apiMock.token = 'tok'
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/bookings' || path === '/api/notifications') return Promise.resolve([])
      return Promise.resolve([])
    })
    return renderApp(route)
  }

  it('shows empty states for bookings and notifications', async () => {
    renderSignedIn('/bookings')
    await waitFor(() => expect(screen.getByRole('heading', { name: '我的预订' })).toBeInTheDocument())
    expect(screen.getByText('还没有预订')).toBeInTheDocument()

    renderSignedIn('/notifications')
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Kafka 消息' })).toBeInTheDocument())
    expect(screen.getByText('还没有消息')).toBeInTheDocument()
  })

  it('renders notifications with a timestamp', async () => {
    apiMock.token = 'tok'
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/notifications') {
        return Promise.resolve([
          { id: 1, bookingId: 1, message: 'Kafka 已处理：BOOKING_CREATED', createdAt: '2026-09-01T00:00:00Z' },
        ])
      }
      return Promise.resolve([])
    })
    renderApp('/notifications')
    await waitFor(() => expect(screen.getByText('Kafka 已处理：BOOKING_CREATED')).toBeInTheDocument())
  })
})
