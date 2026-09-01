import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { AuthProvider, SessionUser } from '../auth'
import { ApiError } from '../api'

const apiMock = vi.hoisted(() => ({ fn: vi.fn(), token: null as string | null }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: apiMock.fn, getAccessToken: () => apiMock.token }
})

const user: SessionUser = { id: 1, email: 'u@t.dev', name: 'U', role: 'USER' }

const notifications = [
  { id: 1, title: '预订确认', message: '摇滚夜 x2', createdAt: '2026-09-01T00:00:00Z' },
  { id: 2, message: '活动时间已更新', createdAt: '2026-09-02T00:00:00Z' },
]

function renderNotifications() {
  return render(
    <MemoryRouter initialEntries={['/notifications']}>
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

describe('NotificationsPage', () => {
  it('renders titled and untitled messages', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/notifications') return Promise.resolve(notifications)
      return Promise.resolve([])
    })
    renderNotifications()
    expect(await screen.findByText('预订确认 · 摇滚夜 x2')).toBeInTheDocument()
    expect(screen.getByText('活动时间已更新')).toBeInTheDocument()
  })

  it('removes a single message once the server accepts the read receipt', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/notifications') return Promise.resolve(notifications)
      return Promise.resolve({})
    })
    renderNotifications()
    await screen.findByText('活动时间已更新')

    await userEvent.click(screen.getAllByRole('button', { name: '标为已读' })[0])
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/notifications/1/read'))
    await waitFor(() => expect(screen.queryByText('预订确认 · 摇滚夜 x2')).not.toBeInTheDocument())
    expect(screen.getByText('活动时间已更新')).toBeInTheDocument()
  })

  it('clears the whole inbox with 全部已读', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/notifications') return Promise.resolve(notifications)
      return Promise.resolve({})
    })
    renderNotifications()
    await screen.findByText('活动时间已更新')

    await userEvent.click(screen.getByRole('button', { name: '全部已读' }))
    await waitFor(() => expect(screen.getByText('还没有消息')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '全部已读' })).not.toBeInTheDocument()
  })

  it('keeps the message and surfaces a toast when the read receipt fails', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/notifications') return Promise.resolve([notifications[0]])
      return Promise.reject(new ApiError(500, '服务暂时不可用'))
    })
    renderNotifications()
    await screen.findByText('预订确认 · 摇滚夜 x2')

    await userEvent.click(screen.getByRole('button', { name: '标为已读' }))
    expect(await screen.findByText('服务暂时不可用')).toBeInTheDocument()
    expect(screen.getByText('预订确认 · 摇滚夜 x2')).toBeInTheDocument()
  })

  it('falls back to an empty inbox when the request fails', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      return Promise.reject(new ApiError(500, 'boom'))
    })
    renderNotifications()
    expect(await screen.findByText('还没有消息')).toBeInTheDocument()
  })
})
