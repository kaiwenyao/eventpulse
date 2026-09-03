import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import App from './App'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, SessionUser } from './auth'
import { ApiError } from './api'

const apiMock = vi.hoisted(() => ({ fn: vi.fn(), token: null as string | null }))
vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>()
  return {
    ...actual,
    api: apiMock.fn,
    getAccessToken: () => apiMock.token,
    uploadFile: vi.fn().mockResolvedValue({ id: 1, publicUrl: '/api/media/images/1' }),
  }
})

const organiser: SessionUser = { id: 2, email: 'o@t.dev', name: 'O', role: 'ORGANISER' }
const user: SessionUser = { id: 1, email: 'u@t.dev', name: 'U', role: 'USER' }
const event = {
  id: 1,
  title: 'Indie Rock Night',
  description: 'd',
  category: 'music',
  city: 'Berlin',
  startsAt: '2026-09-10T12:00:00Z',
  priceCents: 18000,
  capacity: 10,
  sold: 1,
  remaining: 9,
  status: 'DRAFT',
  version: 3,
}

function renderApp(route: string) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  cleanup()
  apiMock.fn.mockReset()
  apiMock.token = 'tok'
})

describe('organiser console overview', () => {
  it('renders dashboard KPIs, the low-stock callout and recent events', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path === '/api/organiser/dashboard') {
        return Promise.resolve({
          eventCount: 4,
          publishedCount: 2,
          sold: 12,
          capacity: 40,
          sellThrough: 30,
          lowStock: ['Indie Rock Night'],
          outboxPending: 2,
        })
      }
      if (path.startsWith('/api/organiser/events')) return Promise.resolve({ records: [event], total: 1 })
      return Promise.resolve([])
    })
    renderApp('/organiser')

    await waitFor(() => expect(screen.getByRole('heading', { name: '主办方工作台' })).toBeInTheDocument())
    expect(await screen.findByText('4')).toBeInTheDocument()
    expect(screen.getByText('其中 2 场已发布')).toBeInTheDocument()
    expect(screen.getByText('30.0%')).toBeInTheDocument()
    expect(screen.getByText('余票告急（≤ 5 张）')).toBeInTheDocument()
    expect(screen.getAllByText('Indie Rock Night').length).toBeGreaterThan(0)
  })

  it('blocks non-organiser accounts from the console', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      return Promise.resolve([])
    })
    renderApp('/organiser')
    await waitFor(() => expect(screen.getByText('没有主办方权限')).toBeInTheDocument())
  })
})

describe('organiser event list', () => {
  it('renders the table and drives search plus status filtering', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path.startsWith('/api/organiser/events')) return Promise.resolve({ records: [event], total: 1 })
      return Promise.resolve([])
    })
    renderApp('/organiser/events')

    await waitFor(() => expect(screen.getByRole('heading', { name: '活动管理' })).toBeInTheDocument())
    const table = await screen.findByRole('table')
    expect(within(table).getByText('Indie Rock Night')).toBeInTheDocument()
    expect(within(table).getByText('草稿')).toBeInTheDocument()

    await userEvent.type(screen.getByPlaceholderText('搜索我的活动…'), 'Night')
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('GET', expect.stringContaining('q=Night')))

    await userEvent.selectOptions(screen.getByLabelText('状态筛选'), 'DRAFT')
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('GET', expect.stringContaining('status=DRAFT')))
  })
})

describe('organiser event form', () => {
  function mockOrganiser(extra: (method: string, path: string) => unknown = () => undefined) {
    apiMock.fn.mockImplementation((method: string, path: string) => {
      const override = extra(method, path)
      if (override !== undefined) return override
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      return Promise.resolve({})
    })
  }

  it('publishes a new event with the scheduled start time, not a hardcoded one', async () => {
    mockOrganiser()
    renderApp('/organiser/events/new')
    await waitFor(() => expect(screen.getByRole('heading', { name: '新建活动' })).toBeInTheDocument())

    await userEvent.clear(screen.getByLabelText('开始时间'))
    await userEvent.type(screen.getByLabelText('开始时间'), '2027-03-01T19:30')
    await userEvent.clear(screen.getByLabelText('结束时间'))
    await userEvent.type(screen.getByLabelText('结束时间'), '2027-03-01T22:00')
    await userEvent.type(screen.getByLabelText('场地'), 'Sound Space')
    await userEvent.click(screen.getByRole('button', { name: '发布活动' }))

    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith(
        'POST',
        '/api/organiser/events',
        expect.objectContaining({
          publish: true,
          venueName: 'Sound Space',
          startsAt: new Date('2027-03-01T19:30').toISOString(),
          endsAt: new Date('2027-03-01T22:00').toISOString(),
        }),
      ),
    )
  })

  it('saves a draft with publish=false', async () => {
    mockOrganiser()
    renderApp('/organiser/events/new')
    await waitFor(() => expect(screen.getByRole('heading', { name: '新建活动' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/organiser/events', expect.objectContaining({ publish: false })),
    )
  })

  it('blocks submission and lists every invalid field', async () => {
    mockOrganiser()
    renderApp('/organiser/events/new')
    await waitFor(() => expect(screen.getByRole('heading', { name: '新建活动' })).toBeInTheDocument())

    await userEvent.clear(screen.getByLabelText('标题'))
    await userEvent.clear(screen.getByLabelText('容量'))
    await userEvent.type(screen.getByLabelText('容量'), '0')
    await userEvent.click(screen.getByRole('button', { name: '发布活动' }))

    expect(await screen.findByText('还有 2 项需要修改')).toBeInTheDocument()
    expect(screen.getByText('标题：请填写活动标题')).toBeInTheDocument()
    expect(screen.getByText('容量：容量必须大于零')).toBeInTheDocument()
    expect(apiMock.fn).not.toHaveBeenCalledWith('POST', '/api/organiser/events', expect.anything())
  })

  it('rejects an end time that precedes the start time', async () => {
    mockOrganiser()
    renderApp('/organiser/events/new')
    await waitFor(() => expect(screen.getByRole('heading', { name: '新建活动' })).toBeInTheDocument())

    await userEvent.clear(screen.getByLabelText('开始时间'))
    await userEvent.type(screen.getByLabelText('开始时间'), '2027-03-01T19:30')
    await userEvent.clear(screen.getByLabelText('结束时间'))
    await userEvent.type(screen.getByLabelText('结束时间'), '2027-03-01T18:00')
    await userEvent.click(screen.getByRole('button', { name: '发布活动' }))

    expect(await screen.findByText('结束时间：结束时间必须晚于开始时间')).toBeInTheDocument()
  })

  it('hydrates the edit form, echoes the version, and surfaces a conflict', async () => {
    mockOrganiser((method, path) => {
      if (path === '/api/organiser/events/8' && method === 'PUT') return Promise.reject(new ApiError(409, 'Event was modified by someone else, refresh and try again'))
      if (path === '/api/organiser/events/8') return Promise.resolve({ ...event, id: 8, status: 'PUBLISHED' })
      return undefined
    })
    renderApp('/organiser/events/8/edit')
    await waitFor(() => expect(screen.getByRole('heading', { name: '编辑活动' })).toBeInTheDocument())

    // Loaded values, not blank defaults — the old form always opened on "新活动".
    await waitFor(() => expect(screen.getByLabelText('标题')).toHaveValue('Indie Rock Night'))
    expect(screen.getByLabelText('城市')).toHaveValue('Berlin')
    expect(screen.getByLabelText('票价（欧元）')).toHaveValue(180)

    await userEvent.type(screen.getByLabelText('标题'), '加')
    await userEvent.type(screen.getByLabelText('摘要'), '短')
    await userEvent.type(screen.getByLabelText('介绍'), '长')
    await userEvent.type(screen.getByLabelText('详细地址'), '一号路')
    await userEvent.type(screen.getByLabelText('联系方式'), 'ops@t.dev')
    await userEvent.type(screen.getByLabelText('参与须知'), '请提前到场')
    await userEvent.clear(screen.getByLabelText('单笔限购'))
    await userEvent.type(screen.getByLabelText('单笔限购'), '4')

    const cover = new File(['x'], 'cover.png', { type: 'image/png' })
    await userEvent.upload(screen.getByLabelText('封面'), cover)
    await waitFor(() => expect(screen.getByAltText('活动封面预览')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith(
        'PUT',
        '/api/organiser/events/8',
        expect.objectContaining({ version: 3, maxQuantityPerBooking: 4, coverUrl: '/api/media/images/1' }),
      ),
    )
    expect(await screen.findByText('Event was modified by someone else, refresh and try again')).toBeInTheDocument()
  })

  it('reports a load failure instead of showing a blank form', async () => {
    mockOrganiser((_method, path) => {
      if (path === '/api/organiser/events/9') return Promise.reject(new ApiError(403, 'You can only manage your own events'))
      return undefined
    })
    renderApp('/organiser/events/9/edit')
    await waitFor(() => expect(screen.getByText('You can only manage your own events')).toBeInTheDocument())
  })
})

describe('organiser event detail lifecycle', () => {
  function mockDetail(detail: Record<string, unknown>) {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path === '/api/organiser/events/1') return Promise.resolve(detail)
      if (path.startsWith('/api/organiser/events')) return Promise.resolve({ records: [event], total: 1 })
      return Promise.resolve({})
    })
  }

  it('publishes and duplicates a draft', async () => {
    mockDetail(event)
    renderApp('/organiser/events/1')
    await waitFor(() => expect(screen.getByLabelText('活动生命周期')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: '复制' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/organiser/events/1/duplicate'))
    cleanup()

    mockDetail(event)
    renderApp('/organiser/events/1')
    await waitFor(() => expect(screen.getByRole('button', { name: '发布' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '发布' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/organiser/events/1/publish'))
  })

  it('requires a reason before cancelling a live event', async () => {
    mockDetail({ ...event, status: 'PUBLISHED' })
    renderApp('/organiser/events/1')
    await waitFor(() => expect(screen.getByRole('button', { name: '取消活动' })).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: '取消活动' }))
    const dialog = await screen.findByRole('dialog')
    // The confirm stays disabled until an explicit reason is supplied.
    expect(within(dialog).getByRole('button', { name: '确认取消活动' })).toBeDisabled()

    await userEvent.type(within(dialog).getByLabelText('取消原因'), 'weather')
    await userEvent.click(within(dialog).getByRole('button', { name: '确认取消活动' }))
    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/organiser/events/1/cancel', { reason: 'weather' }),
    )
  })

  it('archives a finished event through the confirm dialog', async () => {
    mockDetail({ ...event, status: 'FINISHED' })
    renderApp('/organiser/events/1')
    await waitFor(() => expect(screen.getByRole('button', { name: '归档' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '归档' }))
    await userEvent.click(await screen.findByRole('button', { name: '确认归档' }))
    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/organiser/events/1/archive', { note: '归档' }),
    )
  })

  it('deletes a draft only after confirmation, and can be dismissed', async () => {
    mockDetail(event)
    renderApp('/organiser/events/1')
    await waitFor(() => expect(screen.getByRole('button', { name: '删除草稿' })).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: '删除草稿' }))
    await userEvent.click(await screen.findByRole('button', { name: '返回' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(apiMock.fn).not.toHaveBeenCalledWith('DELETE', '/api/organiser/events/1')

    await userEvent.click(screen.getByRole('button', { name: '删除草稿' }))
    await userEvent.click(await screen.findByRole('button', { name: '确认删除' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('DELETE', '/api/organiser/events/1'))
  })

  it('surfaces a failed lifecycle action', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path === '/api/organiser/events/1') return Promise.resolve(event)
      if (path.includes('/publish')) return Promise.reject(new ApiError(409, 'Event cannot be published in its current state'))
      return Promise.resolve({})
    })
    renderApp('/organiser/events/1')
    await waitFor(() => expect(screen.getByRole('button', { name: '发布' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '发布' }))
    expect(await screen.findByText('Event cannot be published in its current state')).toBeInTheDocument()
  })
})

describe('organiser attendees and analytics', () => {
  it('checks a ticket in, filters the roster and reports failures', async () => {
    const rows = [
      { bookingId: 1, ticketId: 11, name: 'Ada', email: 'ada@t.dev', status: 'VALID' },
      { bookingId: 2, ticketId: 12, name: 'Bo', email: 'bo@t.dev', status: 'CHECKED_IN' },
    ]
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path.startsWith('/api/organiser/events/1/attendees')) return Promise.resolve(rows)
      if (path.includes('/check-in')) return Promise.resolve({ id: 11, status: 'CHECKED_IN' })
      return Promise.resolve({})
    })
    renderApp('/organiser/events/1/attendees')
    await waitFor(() => expect(screen.getByRole('heading', { name: '参与者管理' })).toBeInTheDocument())
    expect(await screen.findByText('Ada')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('票码核销'), 'abc')
    await userEvent.click(screen.getByRole('button', { name: '签到' }))
    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/organiser/tickets/check-in', { code: 'abc', source: 'manual' }),
    )

    await userEvent.type(screen.getByLabelText('筛选参与者'), 'bo@')
    await waitFor(() => expect(screen.queryByText('Ada')).not.toBeInTheDocument())
    expect(screen.getByText('Bo')).toBeInTheDocument()
  })

  it('shows the check-in error returned by the server', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path.startsWith('/api/organiser/events/8/attendees')) return Promise.resolve([])
      if (path.includes('/check-in')) return Promise.reject(new ApiError(409, 'Ticket is no longer valid for check-in'))
      return Promise.resolve({})
    })
    renderApp('/organiser/events/8/attendees')
    await waitFor(() => expect(screen.getByRole('heading', { name: '参与者管理' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '签到' }))
    expect(await screen.findByText('Ticket is no longer valid for check-in')).toBeInTheDocument()
  })

  it('renders analytics tiles and the daily trend, and scopes to one event', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path.startsWith('/api/organiser/analytics')) {
        return Promise.resolve({
          views: 30,
          clicks: 12,
          bookings: 3,
          conversion: 10,
          series: [{ metricDate: '2026-08-30', views: 10, clicks: 4, bookings: 1 }],
        })
      }
      if (path.startsWith('/api/organiser/events')) return Promise.resolve({ records: [event], total: 1 })
      return Promise.resolve({})
    })
    renderApp('/organiser/analytics')
    await waitFor(() => expect(screen.getByRole('heading', { name: '数据分析' })).toBeInTheDocument())

    expect(await screen.findByText('30')).toBeInTheDocument()
    expect(screen.getByText('10.0%')).toBeInTheDocument()
    expect(screen.getByLabelText('最近 1 天的每日浏览量')).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('选择活动'), '1')
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('GET', '/api/organiser/analytics?eventId=1'))
  })
})

describe('audience flows', () => {
  it('covers favourites, nearby and booking confirm', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/events/1') return Promise.resolve(event)
      if (path.startsWith('/api/events/nearby') || path.startsWith('/api/events')) {
        return Promise.resolve([event])
      }
      if (path === '/api/favourites') return Promise.resolve({ records: [event] })
      if (path === '/api/bookings') return Promise.resolve({ id: 9 })
      if (path.includes('/favourite')) return Promise.resolve({})
      return Promise.resolve([])
    })
    renderApp('/')
    await waitFor(() => expect(screen.getByText('Indie Rock Night')).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText('城市'), 'Berlin')
    await userEvent.selectOptions(screen.getByLabelText('排序'), 'price')
    await userEvent.click(screen.getByRole('button', { name: '附近' }))
    cleanup()

    renderApp('/favourites')
    await waitFor(() => expect(screen.getByRole('heading', { name: '我的收藏' })).toBeInTheDocument())
    await waitFor(() => expect(screen.getAllByText('Indie Rock Night').length).toBeGreaterThan(0))
    cleanup()

    renderApp('/events/1')
    await waitFor(() => expect(screen.getByRole('button', { name: '收藏活动' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '收藏活动' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '取消收藏' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '增加一张' }))
    await userEvent.click(screen.getByRole('button', { name: '确认预订' }))
    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/bookings', { eventId: 1, quantity: 2 }),
    )
  })

  it('cancels a booking from the order detail page', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/bookings/1') {
        return Promise.resolve({ id: 1, eventId: 1, eventTitle: 'Indie Rock Night', quantity: 2, status: 'CONFIRMED', createdAt: '2026-09-01T00:00:00Z' })
      }
      if (path === '/api/bookings/1/tickets') return Promise.resolve([{ id: 11, code: 'abc123', status: 'VALID' }])
      if (path.includes('/cancel')) {
        return Promise.resolve({ id: 1, eventId: 1, eventTitle: 'Indie Rock Night', quantity: 2, status: 'CANCELLED', createdAt: '2026-09-01T00:00:00Z' })
      }
      return Promise.resolve([])
    })
    renderApp('/bookings/1')
    await waitFor(() => expect(screen.getByRole('button', { name: '取消订单' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '取消订单' }))
    await waitFor(() => expect(screen.getByText('已取消')).toBeInTheDocument())
  })

  it('renders a not-found page for unknown routes', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      return Promise.resolve([])
    })
    renderApp('/nope')
    await waitFor(() => expect(screen.getByText('页面不存在')).toBeInTheDocument())
  })
})
