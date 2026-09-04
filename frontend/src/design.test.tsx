import { render, screen, waitFor, within } from '@testing-library/react'
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
  title: 'City Pulse · Indie Rock Night',
  description: 'd',
  category: 'music',
  city: 'Berlin',
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
    await waitFor(() => expect(screen.getByText('City Pulse · Indie Rock Night')).toBeInTheDocument())
    // Category pill, city label and sold meter label render on the stub.
    expect(document.querySelector('.pill-music')).not.toBeNull()
    expect(screen.getAllByText('Berlin').length).toBeGreaterThan(0)
    expect(screen.getByText('1 张余票')).toBeInTheDocument()
    expect(document.querySelector('.sold-fill')).not.toBeNull()
  })

  it('filters by category through the category dropdown', async () => {
    apiMock.fn.mockResolvedValue([])
    renderApp()
    const select = await screen.findByRole('combobox', { name: '按分类筛选' })
    await userEvent.selectOptions(select, 'music')
    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith('GET', expect.stringContaining('category=music')),
    )
  })

  it('offers every fixed category in the dropdown, and nothing else', async () => {
    apiMock.fn.mockResolvedValue([])
    renderApp()
    const select = await screen.findByRole('combobox', { name: '按分类筛选' })
    // 「全部分类」+ 8 个固定分类，不多不少：多出来的一定是漏改的硬编码。
    expect(within(select).getAllByRole('option').map((o) => (o as HTMLOptionElement).value)).toEqual([
      '',
      'music',
      'tech',
      'sports',
      'art',
      'food',
      'business',
      'community',
      'other',
    ])
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
    await waitFor(() => expect(screen.getByText('City Pulse · Indie Rock Night')).toBeInTheDocument())
    await userEvent.type(screen.getByPlaceholderText('搜索活动…'), 'zzz')
    await waitFor(() => expect(screen.getByText('还没有活动')).toBeInTheDocument())
  })
})

describe('event detail', () => {
  it('renders price, remaining tickets and the booking CTA', async () => {
    apiMock.fn.mockResolvedValue(event)
    renderApp('/events/1')
    await waitFor(() => expect(screen.getByText('City Pulse · Indie Rock Night')).toBeInTheDocument())
    expect(screen.getByText('€180.00')).toBeInTheDocument()
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
    // The list renders a skeleton first, so the empty state settles a tick later.
    expect(await screen.findByText('还没有预订')).toBeInTheDocument()

    renderSignedIn('/notifications')
    await waitFor(() => expect(screen.getByRole('heading', { name: '消息中心' })).toBeInTheDocument())
    expect(await screen.findByText('还没有消息')).toBeInTheDocument()
  })

  it('renders notifications with a timestamp', async () => {
    apiMock.token = 'tok'
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/notifications') {
        return Promise.resolve([
          { id: 1, bookingId: 1, message: 'Processed: BOOKING_CREATED', createdAt: '2026-09-01T00:00:00Z' },
        ])
      }
      return Promise.resolve([])
    })
    renderApp('/notifications')
    await waitFor(() => expect(screen.getByText('Processed: BOOKING_CREATED')).toBeInTheDocument())
  })

  it('renders booking detail tickets and organiser analytics', async () => {
    apiMock.token = 'tok'
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/bookings/1') {
        return Promise.resolve({ id: 1, eventId: 1, eventTitle: 'Indie Rock Night', quantity: 2, status: 'CONFIRMED', createdAt: '2026-09-01T00:00:00Z' })
      }
      if (path === '/api/bookings/1/tickets') {
        return Promise.resolve([{ id: 11, code: 'abc123', status: 'VALID' }])
      }
      if (path === '/api/organiser/analytics') return Promise.resolve({ views: 3, clicks: 2, bookings: 1, conversion: 10 })
      if (path === '/api/organiser/dashboard') return Promise.resolve({ eventCount: 1, sold: 2, sellThrough: 20 })
      if (path.startsWith('/api/organiser/events')) return Promise.resolve({ records: [event], total: 1 })
      return Promise.resolve([])
    })
    renderApp('/bookings/1')
    await waitFor(() => expect(screen.getByRole('heading', { name: '订单详情' })).toBeInTheDocument())
    expect(screen.getByText(/票 #11/)).toBeInTheDocument()
  })
})
