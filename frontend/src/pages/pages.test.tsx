import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import Discovery from './Discovery'
import EventDetail from './EventDetail'
import Checkout from './Checkout'
import BookingDetail from './BookingDetail'
import Orders from './Orders'
import Organiser from './Organiser'
import Redeem from './Redeem'
import Admin from './Admin'
import App from '../App'

const apiMock = vi.hoisted(() => ({ fn: vi.fn() }))
const authMock = vi.hoisted(() => ({
  user: null as { id: string; email: string; role: string; displayName: string } | null,
  ready: true, logout: vi.fn(), login: vi.fn(), register: vi.fn(), refresh: vi.fn(),
}))

vi.mock('../api', async (importOriginal) => ({ ...(await importOriginal<typeof import('../api')>()), api: apiMock.fn }))
vi.mock('../auth', () => ({ useAuth: () => authMock }))

const event = {
  id: 'event-1', title: '城市音乐节', description: '现场演出', category: 'music', status: 'PUBLISHED',
  startsAt: '2099-06-01T12:00:00Z', endsAt: '2099-06-01T15:00:00Z', ageRequirement: null,
  policyVersion: 1, policy: {}, venueName: '大剧院', city: '上海', organiserName: '主办方',
  tiers: [{ id: 'tier-1', name: '标准票', unitPriceMinor: 10000, currency: 'CNY',
    saleStartAt: '2000-01-01T00:00:00Z', saleEndAt: '2099-12-31T00:00:00Z', perUserLimit: 5,
    status: 'ACTIVE', capacity: 100, available: 42, sold: 0 }],
}
const booking = {
  id: 'booking-1', eventId: 'event-1', tierName: '标准票', quantity: 1, status: 'PAYMENT_PENDING',
  entitlementStatus: 'ACTIVE', refundState: 'NONE', totalMinor: 10000, currency: 'CNY',
  priceSnapshot: { totalMinor: 10000 }, policySnapshot: { policyVersion: 1 },
  expiresAt: '2099-06-01T12:00:00Z', activeIntent: null,
  refunds: [], tickets: [],
}

function renderPage(node: React.ReactNode, route: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const pattern = route.startsWith('/events/') ? '/events/:id'
    : route.startsWith('/checkout/') ? '/checkout/:bookingId'
      : route.startsWith('/bookings/') ? '/bookings/:id' : '*'
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[route]}>
    <Routes><Route path={pattern} element={node} /></Routes>
  </MemoryRouter></QueryClientProvider>)
}

beforeEach(() => {
  authMock.user = { id: 'user-1', email: 'user@example.test', role: 'USER', displayName: 'Test User' }
  authMock.ready = true
  authMock.logout.mockReset().mockResolvedValue(undefined)
  apiMock.fn.mockReset()
  apiMock.fn.mockImplementation((_method: string, path: string) => {
    if (path.startsWith('/api/v1/events?')) return Promise.resolve({ items: [
      { id: 'event-1', title: '城市音乐节', category: 'music', startsAt: event.startsAt, endsAt: event.endsAt,
        venueName: '大剧院', city: '上海', minPriceMinor: 10000, currency: 'CNY', available: 42 },
    ], nextCursor: null })
    if (path === '/api/v1/recommendations?section=for-you&limit=6') return Promise.resolve({
      requestId: 'r1', modelVersion: 'v1', items: [{ eventId: 'event-1', title: '推荐音乐节', category: 'music',
        startsAt: event.startsAt, city: '上海', score: 0.9, reasonCodes: ['同城'] }],
    })
    if (path === '/api/v1/events/event-1') return Promise.resolve(event)
    if (path === '/api/v1/bookings/booking-1') return Promise.resolve(booking)
    if (path === '/api/v1/bookings') return Promise.resolve([{ id: 'booking-1', tierName: '标准票', quantity: 1,
      status: 'CONFIRMED', refundState: 'NONE', totalMinor: 10000, currency: 'CNY', expiresAt: booking.expiresAt }])
    if (path === '/api/v1/organiser/funnel') return Promise.resolve([])
    if (path === '/api/v1/admin/reauth') return Promise.resolve({ reauthToken: 'reauth-1' })
    if (path === '/api/v1/admin/exceptions') return Promise.resolve({ manualReviewCommands: [], unknownCommands: [],
      failedRefunds: [], unknownPayments: [], openConsumerGaps: [], outboxOldestPendingSeconds: 0,
      commandsRunningLeases: [] })
    if (path === '/api/v1/bookings/booking-1/tickets/reveal') return Promise.resolve({ tokens: ['ticket-token'] })
    if (path === '/api/v1/organiser/tickets/redeem') return Promise.resolve({ result: 'OK', ticketId: 't1',
      bookingId: 'booking-1', eventId: 'event-1', eventTitle: '城市音乐节', sequence: 1,
      usedAt: '2099-06-01T12:00:00Z' })
    return Promise.resolve({ id: 'booking-1' })
  })
})

describe('customer pages', () => {
  it('searches discovery and shows recommendations and results', async () => {
    renderPage(<Discovery />, '/')
    expect(await screen.findByText('城市音乐节')).toBeInTheDocument()
    expect(screen.getByText('推荐音乐节')).toBeInTheDocument()
    fireEvent.change(screen.getAllByRole('textbox')[0], { target: { value: '音乐' } })
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('GET', expect.stringContaining('q=%E9%9F%B3%E4%B9%90')))
  })

  it('favourites an event, selects a tier and creates a booking', async () => {
    renderPage(<EventDetail />, '/events/event-1')
    expect(await screen.findByText('城市音乐节')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '收藏活动' }))
    expect(await screen.findByRole('button', { name: '已收藏' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '选择' }))
    fireEvent.click(screen.getByRole('button', { name: '创建预订' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/v1/bookings', expect.anything(), expect.anything()))
    cleanup()
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/v1/events/event-1') return Promise.resolve(event)
      if (path.includes('/me/saved-events/')) return Promise.reject(new Error('save unavailable'))
      return Promise.resolve({})
    })
    renderPage(<EventDetail />, '/events/event-1')
    fireEvent.click(await screen.findByRole('button', { name: '收藏活动' }))
    expect(await screen.findByText('网络错误')).toBeInTheDocument()
  })

  it('pays a pending booking with an idempotency key and displays failures', async () => {
    renderPage(<Checkout />, '/checkout/booking-1')
    expect(await screen.findByText('发起支付')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '发起支付' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/v1/bookings/booking-1/pay', {}, expect.anything()))
    cleanup()
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/v1/bookings/booking-1') return Promise.resolve(booking)
      if (path.endsWith('/pay')) return Promise.reject(new Error('gateway unavailable'))
      return Promise.resolve({})
    })
    renderPage(<Checkout />, '/checkout/booking-1')
    fireEvent.click(await screen.findByRole('button', { name: '发起支付' }))
    expect(await screen.findByText('网络错误')).toBeInTheDocument()
  })

  it('shows orders and booking details, including ticket reveal and cancel', async () => {
    renderPage(<Orders />, '/orders')
    expect(await screen.findByText('我的订单')).toBeInTheDocument()

    const detailBooking = { ...booking, status: 'CONFIRMED', refunds: [{ id: 'r1', amountMinor: 10000, state: 'FAILED' }],
      tickets: [{ id: 't1', sequence: 1, status: 'ACTIVE', usedAt: null }] }
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/v1/bookings/booking-1') return Promise.resolve(detailBooking)
      if (path.endsWith('/tickets/reveal')) return Promise.resolve({ tokens: ['ticket-token'] })
      return Promise.resolve({})
    })
    vi.stubGlobal('confirm', vi.fn(() => true))
    cleanup()
    renderPage(<BookingDetail />, '/bookings/booking-1')
    expect(await screen.findByText('退款记录')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '显示票券二维码' }))
    expect(await screen.findByText('ticket-token')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '取消订单' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/v1/bookings/booking-1/cancel', expect.anything(), expect.anything()))
    vi.stubGlobal('confirm', vi.fn(() => false))
    fireEvent.click(screen.getByRole('button', { name: '取消订单' }))
    vi.unstubAllGlobals()
  })
})

describe('application shell and edge states', () => {
  it('renders the authenticated shell and discovery route', async () => {
    renderPage(<App />, '/')
    expect(screen.getByRole('link', { name: 'EventPulse' })).toBeInTheDocument()
    expect(await screen.findByText('发现活动')).toBeInTheDocument()
    expect(screen.getByText('我的订单')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '退出' }))
    await waitFor(() => expect(authMock.logout).toHaveBeenCalled())
  })

  it('redirects protected routes to login when logged out and shows loading state', async () => {
    authMock.user = null
    renderPage(<App />, '/orders')
    expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument()
    cleanup()
    authMock.ready = false
    renderPage(<App />, '/')
    expect(screen.getByText('加载中…')).toBeInTheDocument()
    authMock.ready = true
    authMock.user = { id: 'user-1', email: 'user@example.test', role: 'USER', displayName: 'Test User' }
  })

  it('renders a paid booking and a timed-out checkout', async () => {
    cleanup()
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/v1/bookings/booking-1') return Promise.resolve({ ...booking,
        status: 'CONFIRMED', activeIntent: { id: 'pi', state: 'SUCCEEDED', providerKey: 'pi-1' } })
      return Promise.resolve({})
    })
    renderPage(<Checkout />, '/checkout/booking-1')
    expect(await screen.findByText(/出票成功/)).toBeInTheDocument()
    cleanup()
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/v1/bookings/booking-1') return Promise.resolve({ ...booking, status: 'EXPIRED' })
      return Promise.resolve({})
    })
    renderPage(<Checkout />, '/checkout/booking-1')
    expect(await screen.findByText(/订单状态：EXPIRED/)).toBeInTheDocument()
  })

  it('shows admin reauthentication errors', async () => {
    apiMock.fn.mockRejectedValue(new Error('reauth unavailable'))
    renderPage(<Admin />, '/admin')
    fireEvent.change(document.querySelector('input[placeholder="再次输入管理员密码"]')!, { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: '重新认证' }))
    expect(await screen.findByText('网络错误')).toBeInTheDocument()
  })

  it('shows admin recovery rows and calls retry and dry-run actions', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/v1/admin/reauth') return Promise.resolve({ reauthToken: 'reauth-1' })
      if (path === '/api/v1/admin/exceptions') return Promise.resolve({
        manualReviewCommands: [{ id: 'cmd-1', kind: 'REFUND', attempts: 8, last_error: 'failed' }],
        unknownCommands: [{ id: 'cmd-2', kind: 'CAPTURE', next_attempt_at: 'soon' }],
        failedRefunds: [{ id: 'ref-1', booking_id: 'booking-1', state: 'FAILED' }], unknownPayments: [],
        openConsumerGaps: [{ id: 'gap-1', aggregate_id: 'booking-1', expected: 2, received: 3 }],
        outboxOldestPendingSeconds: 12, commandsRunningLeases: [],
      })
      return Promise.resolve({})
    })
    renderPage(<Admin />, '/admin')
    vi.spyOn(window, 'alert').mockImplementation(() => {})
    fireEvent.change(document.querySelector('input[placeholder="再次输入管理员密码"]')!, { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: '重新认证' }))
    expect(await screen.findByText('cmd-1…')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    fireEvent.click(screen.getByRole('button', { name: 'dry-run' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/v1/admin/commands/cmd-1/retry', expect.anything(), expect.anything()))
    expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/v1/admin/consumer-gaps/gap-1/resolve', expect.anything(), expect.anything())
    vi.restoreAllMocks()
  })
})

describe('operator pages', () => {
  it('covers empty catalogue and order states', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path.startsWith('/api/v1/events?')) return Promise.resolve({ items: [], nextCursor: null })
      if (path === '/api/v1/recommendations?section=for-you&limit=6') return Promise.resolve({ items: [] })
      if (path === '/api/v1/bookings') return Promise.resolve([])
      return Promise.resolve({})
    })
    renderPage(<Discovery />, '/')
    expect(await screen.findByText('没有符合条件的活动。')).toBeInTheDocument()
    cleanup()
    renderPage(<Orders />, '/orders')
    expect(await screen.findByText('还没有订单。')).toBeInTheDocument()
  })

  it('renders organiser funnel and creates an event', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/v1/organiser/funnel') return Promise.resolve([{ eventId: 'e1', title: '已有活动', status: 'PUBLISHED',
        startsAt: event.startsAt, views: 10, saves: 2, bookingsCreated: 3, bookingsConfirmed: 2, ticketsIssued: 2 }])
      if (path.startsWith('/api/v1/events?')) return Promise.resolve({ items: [{ id: 'e2', title: '新活动' }] })
      return Promise.resolve({})
    })
    renderPage(<Organiser />, '/organiser')
    expect(await screen.findByText('已有活动')).toBeInTheDocument()
    const inputs = screen.getAllByRole('textbox')
    fireEvent.change(inputs[0], { target: { value: '新活动' } })
    fireEvent.change(inputs[1], { target: { value: '活动描述' } })
    inputs.slice(2).forEach((input, index) => fireEvent.change(input, { target: { value: index % 2 ? '1' : '上海' } }))
    fireEvent.change(screen.getAllByRole('combobox')[0], { target: { value: 'tech' } })
    const dateInputs = document.querySelectorAll('input[type="datetime-local"]')
    fireEvent.change(dateInputs[0], { target: { value: '2099-06-01T10:00' } })
    fireEvent.change(dateInputs[1], { target: { value: '2099-06-01T12:00' } })
    fireEvent.click(screen.getByRole('button', { name: '创建并发布' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/v1/organiser/events', expect.anything()))
  })

  it('redeems a ticket and shows both success and failure', async () => {
    renderPage(<Redeem />, '/redeem')
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'raw-ticket' } })
    fireEvent.click(screen.getByRole('button', { name: '核销' }))
    expect(await screen.findByText('核销成功')).toBeInTheDocument()
    cleanup()
    apiMock.fn.mockRejectedValue(new Error('redeem unavailable'))
    renderPage(<Redeem />, '/redeem')
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'raw-ticket' } })
    fireEvent.click(screen.getByRole('button', { name: '核销' }))
    expect(await screen.findByText('网络错误')).toBeInTheDocument()
  })

  it('reauthenticates and loads admin queues', async () => {
    renderPage(<Admin />, '/admin')
    fireEvent.change(document.querySelector('input[placeholder="再次输入管理员密码"]')!, { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: '重新认证' }))
    expect(await screen.findByText('人工处理队列（MANUAL_REVIEW）')).toBeInTheDocument()
  })
})
