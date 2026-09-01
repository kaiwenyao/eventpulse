import { cleanup, render, screen, waitFor } from '@testing-library/react'
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
  title: '摇滚夜',
  description: 'd',
  category: 'music',
  city: '上海',
  startsAt: '2026-09-10T12:00:00Z',
  priceCents: 18000,
  capacity: 10,
  sold: 1,
  remaining: 9,
  status: 'DRAFT',
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

describe('organiser and user crud pages', () => {
  it('fills dashboard quick-create fields', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path === '/api/organiser/dashboard') return Promise.resolve({ eventCount: 1, sold: 1, sellThrough: 10 })
      if (path.startsWith('/api/organiser/events')) return Promise.resolve({ records: [], total: 0 })
      return Promise.resolve([])
    })
    renderApp('/organiser')
    await waitFor(() => expect(screen.getByRole('heading', { name: '主办方工作台' })).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText('城市'), '北')
    await userEvent.type(screen.getByLabelText('分类'), 'art')
    await userEvent.clear(screen.getByLabelText('容量'))
    await userEvent.type(screen.getByLabelText('容量'), '20')
    await userEvent.clear(screen.getByLabelText('票价（元）'))
    await userEvent.type(screen.getByLabelText('票价（元）'), '12')
    await userEvent.type(screen.getByPlaceholderText('搜索我的活动…'), '夜')
    await userEvent.selectOptions(screen.getByLabelText('状态筛选'), 'DRAFT')
  })

  it('covers organiser list, form, detail, attendees and analytics', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path === '/api/organiser/dashboard') return Promise.resolve({ eventCount: 1, sold: 2, sellThrough: 20 })
      if (path.startsWith('/api/organiser/events/1/attendees')) {
        return Promise.resolve([{ bookingId: 1, ticketId: 11, name: 'U', email: 'u@t.dev', status: 'VALID' }])
      }
      if (path === '/api/organiser/events/1' || path.startsWith('/api/organiser/events/1?')) {
        return Promise.resolve(event)
      }
      if (path.startsWith('/api/organiser/events')) return Promise.resolve({ records: [event], total: 1 })
      if (path === '/api/organiser/analytics') return Promise.resolve({ views: 3, clicks: 2, bookings: 1, conversion: 10 })
      if (path.includes('/publish') || path.includes('/cancel') || path.includes('/archive') || path.includes('/duplicate')) {
        return Promise.resolve({ ...event, status: 'PUBLISHED' })
      }
      if (path.includes('/check-in')) return Promise.resolve({ id: 11, status: 'CHECKED_IN' })
      return Promise.resolve({})
    })

    renderApp('/organiser/events')
    await waitFor(() => expect(screen.getByRole('heading', { name: '活动管理' })).toBeInTheDocument())
    await waitFor(() => expect(screen.getAllByText('摇滚夜').length).toBeGreaterThan(0))
    await userEvent.type(screen.getByPlaceholderText('搜索我的活动…'), '夜')
    await userEvent.selectOptions(screen.getByLabelText('状态筛选'), 'DRAFT')
    cleanup()

    renderApp('/organiser/events/new')
    await waitFor(() => expect(screen.getByRole('heading', { name: '新建活动' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '发布活动' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/organiser/events', expect.anything()))
    cleanup()
    renderApp('/organiser/events/new')
    await waitFor(() => expect(screen.getByRole('heading', { name: '新建活动' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    cleanup()

    renderApp('/organiser/events/1')
    await waitFor(() => expect(screen.getByText('草稿 → 已发布/售票中 → 进行中 → 已结束 → 已归档')).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '复制' }))
    cleanup()
    renderApp('/organiser/events/1')
    await waitFor(() => expect(screen.getByRole('button', { name: '发布' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '发布' }))
    cleanup()

    renderApp('/organiser/events/1/attendees')
    await waitFor(() => expect(screen.getByRole('heading', { name: '参与者管理' })).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText('票码核销'), 'abc')
    await userEvent.click(screen.getByRole('button', { name: '签到' }))
    cleanup()

    renderApp('/organiser/analytics')
    await waitFor(() => expect(screen.getByRole('heading', { name: '数据分析' })).toBeInTheDocument())
    expect(screen.getByText(/浏览 3/)).toBeInTheDocument()
  })

  it('covers favourites, nearby, recommend and booking confirm', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path.startsWith('/api/events/nearby') || path.startsWith('/api/recommendations') || path.startsWith('/api/events')) {
        return Promise.resolve([event])
      }
      if (path === '/api/favourites') return Promise.resolve({ records: [event] })
      if (path === '/api/bookings') return Promise.resolve({ id: 9 })
      if (path.includes('/favourite')) return Promise.resolve({})
      return Promise.resolve([])
    })
    renderApp('/')
    await waitFor(() => expect(screen.getByText('摇滚夜')).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText('城市'), '上海')
    await userEvent.selectOptions(screen.getByLabelText('排序'), 'price')
    await userEvent.click(screen.getByRole('button', { name: '附近' }))
    await userEvent.click(screen.getByRole('button', { name: '推荐' }))
    cleanup()

    renderApp('/favourites')
    await waitFor(() => expect(screen.getByRole('heading', { name: '我的收藏' })).toBeInTheDocument())
    await waitFor(() => expect(screen.getAllByText('摇滚夜').length).toBeGreaterThan(0))
    cleanup()

    renderApp('/events/1')
    await waitFor(() => expect(screen.getByRole('button', { name: '收藏活动' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '收藏活动' }))
    await userEvent.click(screen.getByRole('button', { name: '确认预订' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/bookings', expect.anything()))
    cleanup()
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/bookings/1') {
        return Promise.resolve({ id: 1, eventId: 1, eventTitle: '摇滚夜', quantity: 2, status: 'CONFIRMED', createdAt: '2026-09-01T00:00:00Z' })
      }
      if (path === '/api/bookings/1/tickets') return Promise.resolve([{ id: 11, code: 'abc123', status: 'VALID' }])
      if (path.includes('/cancel')) {
        return Promise.resolve({ id: 1, eventId: 1, eventTitle: '摇滚夜', quantity: 2, status: 'CANCELLED', createdAt: '2026-09-01T00:00:00Z' })
      }
      return Promise.resolve([])
    })
    renderApp('/bookings/1')
    await waitFor(() => expect(screen.getByRole('button', { name: '取消订单' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '取消订单' }))
  })

  it('fills organiser form fields, edits, and handles errors', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path === '/api/organiser/events/8' && _m === 'PUT') return Promise.reject(new ApiError(409, '冲突'))
      if (path === '/api/organiser/events/8') return Promise.resolve({ ...event, id: 8, status: 'PUBLISHED' })
      if (path.includes('/check-in')) return Promise.reject(new ApiError(409, '已核销'))
      if (path.startsWith('/api/organiser/events/8/attendees')) return Promise.resolve([])
      return Promise.resolve({})
    })
    renderApp('/organiser/events/8/edit')
    await waitFor(() => expect(screen.getByRole('heading', { name: '编辑活动' })).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText('标题'), '加')
    await userEvent.type(screen.getByLabelText('摘要'), '短')
    await userEvent.type(screen.getByLabelText('介绍'), '长')
    await userEvent.type(screen.getByLabelText('城市'), '市')
    await userEvent.type(screen.getByLabelText('场地'), '馆')
    await userEvent.type(screen.getByLabelText('分类'), 'x')
    await userEvent.clear(screen.getByLabelText('容量'))
    await userEvent.type(screen.getByLabelText('容量'), '1')
    await userEvent.clear(screen.getByLabelText('票价（元）'))
    await userEvent.type(screen.getByLabelText('票价（元）'), '10')
    const cover = new File(['x'], 'cover.png', { type: 'image/png' })
    await userEvent.upload(screen.getByLabelText('封面'), cover)
    await userEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await waitFor(() => expect(screen.getByText('冲突')).toBeInTheDocument())
    cleanup()
    renderApp('/organiser/events/8/attendees')
    await waitFor(() => expect(screen.getByRole('heading', { name: '参与者管理' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '签到' }))
    await waitFor(() => expect(screen.getByText('已核销')).toBeInTheDocument())
  })

  it('cancels a published event', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path === '/api/organiser/events/4') return Promise.resolve({ ...event, id: 4, status: 'PUBLISHED' })
      if (path.includes('/cancel')) return Promise.resolve({ ...event, status: 'CANCELLED' })
      return Promise.resolve({})
    })
    vi.spyOn(window, 'prompt').mockReturnValue('天气')
    renderApp('/organiser/events/4')
    await waitFor(() => expect(screen.getByRole('button', { name: '取消活动' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '取消活动' }))
  })

  it('archives finished events and deletes drafts', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(organiser)
      if (path === '/api/organiser/events/2') return Promise.resolve({ ...event, id: 2, status: 'FINISHED' })
      if (path === '/api/organiser/events/3') return Promise.resolve({ ...event, id: 3, status: 'DRAFT' })
      return Promise.resolve({})
    })
    renderApp('/organiser/events/2')
    await waitFor(() => expect(screen.getByRole('button', { name: '归档' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: '归档' }))
    cleanup()
    renderApp('/organiser/events/3')
    await waitFor(() => expect(screen.getByRole('button', { name: '删除草稿' })).toBeInTheDocument())
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await userEvent.click(screen.getByRole('button', { name: '删除草稿' }))
  })
})
