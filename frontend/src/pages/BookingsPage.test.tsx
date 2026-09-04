import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { AuthProvider, SessionUser } from '../auth'

const apiMock = vi.hoisted(() => ({ fn: vi.fn(), token: 'tok' as string | null }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: apiMock.fn, getAccessToken: () => apiMock.token }
})

vi.mock('../lib/sse', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/sse')>()
  return {
    ...actual,
    streamUserEvents: vi.fn().mockResolvedValue(undefined),
    streamBookingEvents: vi.fn().mockResolvedValue(undefined),
  }
})

const user: SessionUser = { id: 1, email: 'u@t.dev', name: '阿达', role: 'USER' }

const profile = {
  id: 1,
  email: 'u@t.dev',
  name: '阿达',
  role: 'USER',
  walletCents: 50000,
  totalSpentCents: 36000,
  bookingCount: 7,
  ticketCount: 9,
  favouriteCount: 0,
  notificationCount: 0,
}

/** 已确认、服务端允许取消、两张票待核销。 */
const confirmed = {
  id: 1042,
  eventId: 1,
  eventTitle: 'Indie Rock Night',
  eventStartsAt: '2026-09-10T18:00:00Z',
  quantity: 2,
  unitPriceCents: 18000,
  paidCents: 36000,
  status: 'CONFIRMED',
  createdAt: '2026-09-01T12:00:00Z',
  validCount: 2,
  checkedInCount: 0,
  checkoutId: 77,
  cancellable: true,
}

/** 同样是 CONFIRMED，但服务端说不可取消 —— 已有电子票核销入场。 */
const lockedIn = {
  id: 1039,
  eventId: 2,
  eventTitle: 'Tech Summit',
  eventStartsAt: '2026-09-18T07:30:00Z',
  quantity: 1,
  paidCents: 18000,
  status: 'CONFIRMED',
  createdAt: '2026-08-28T10:05:00Z',
  validCount: 0,
  checkedInCount: 1,
  cancellable: false,
  cancelBlockReason: 'TICKET_CHECKED_IN',
}

function mockApi(records: unknown[]) {
  apiMock.fn.mockImplementation((_method: string, path: string) => {
    if (path === '/api/auth/me') return Promise.resolve(user)
    if (path === '/api/auth/profile') return Promise.resolve(profile)
    if (path.startsWith('/api/bookings?')) return Promise.resolve({ records, total: records.length })
    return Promise.resolve({})
  })
}

function renderBookings() {
  return render(
    <MemoryRouter initialEntries={['/bookings']}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  apiMock.fn.mockReset()
  apiMock.token = 'tok'
})

afterEach(cleanup)

describe('BookingsPage', () => {
  it('shows account-wide KPIs rather than a per-page aggregate', async () => {
    mockApi([confirmed])
    renderBookings()

    expect(await screen.findByText('订单总数')).toBeInTheDocument()
    // 7 / 9 都来自账户资料，不是当前页那一条订单。
    expect(screen.getByText('7')).toBeInTheDocument()
    expect(screen.getByText('9')).toBeInTheDocument()
  })

  it('surfaces the event time and check-in progress on each row', async () => {
    mockApi([confirmed, lockedIn])
    renderBookings()

    expect(await screen.findByText('Indie Rock Night')).toBeInTheDocument()
    // 活动时间此前完全没有渲染过。
    expect(screen.getAllByText(/2026/).length).toBeGreaterThan(0)
    expect(screen.getByText('2 张待核销')).toBeInTheDocument()
    expect(screen.getByText('核销 1/1')).toBeInTheDocument()
    expect(screen.getByText('同次结算 #77')).toBeInTheDocument()
  })

  it('gates the cancel button on the server cancellable flag, not the status string', async () => {
    mockApi([confirmed, lockedIn])
    renderBookings()

    await screen.findByText('Indie Rock Night')

    // 两笔都是 CONFIRMED，但只有服务端标记可取消的那笔给出按钮。
    expect(screen.getAllByRole('button', { name: '取消' })).toHaveLength(1)
    expect(screen.getByText('已有电子票核销入场，不能退款')).toBeInTheDocument()
  })

  it('asks for confirmation before cancelling, and only then calls the API', async () => {
    mockApi([confirmed])
    renderBookings()

    await screen.findByText('Indie Rock Night')
    await userEvent.click(screen.getByRole('button', { name: '取消' }))

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('确认取消订单？')).toBeInTheDocument()
    expect(apiMock.fn).not.toHaveBeenCalledWith('POST', '/api/bookings/1042/cancel')

    await userEvent.click(within(dialog).getByRole('button', { name: '取消订单' }))

    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/bookings/1042/cancel'))
  })

  it('filters by status through the chip bar', async () => {
    mockApi([confirmed])
    renderBookings()

    await screen.findByText('Indie Rock Night')
    await userEvent.click(screen.getByRole('button', { name: '已取消' }))

    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith('GET', expect.stringContaining('status=CANCELLED')),
    )
  })

  it('reports a load failure separately from an empty list', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/auth/profile') return Promise.resolve(profile)
      return Promise.reject(new Error('boom'))
    })
    renderBookings()

    expect(await screen.findByText('订单加载失败')).toBeInTheDocument()
    expect(screen.queryByText('还没有预订')).not.toBeInTheDocument()
  })
})
