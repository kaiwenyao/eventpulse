import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { ApiError } from '../api'
import { AuthProvider, SessionUser } from '../auth'

const apiMock = vi.hoisted(() => ({ fn: vi.fn(), token: null as string | null }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: apiMock.fn, getAccessToken: () => apiMock.token }
})

const user: SessionUser = { id: 1, email: 'u@t.dev', name: 'U', role: 'USER' }

function renderLogin() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
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

describe('LoginPage', () => {
  it('fills the form from a demo account chip', async () => {
    apiMock.fn.mockResolvedValue([])
    renderLogin()
    await waitFor(() => expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: '主办方' }))
    expect(screen.getByLabelText('邮箱')).toHaveValue('organiser@eventpulse.dev')
    expect(screen.getByLabelText('密码')).toHaveValue('Organiser123456')
  })

  it('registers a new account with the display name', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/register') return Promise.resolve({ token: 't', user })
      return Promise.resolve([])
    })
    renderLogin()
    await waitFor(() => expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: '去注册' }))
    await userEvent.type(screen.getByLabelText('邮箱'), 'n@t.dev')
    await userEvent.type(screen.getByLabelText('密码'), 'Password123')
    await userEvent.type(screen.getByLabelText('昵称'), '新用户')
    await userEvent.click(screen.getByRole('button', { name: '注册' }))

    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith(
        'POST',
        '/api/auth/register',
        expect.objectContaining({ email: 'n@t.dev', name: '新用户' }),
      ),
    )
  })

  it('shows the server error inline and lets the toast be dismissed', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/login') return Promise.reject(new ApiError(401, 'Invalid email or password'))
      return Promise.resolve([])
    })
    renderLogin()
    await waitFor(() => expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText('邮箱'), 'u@t.dev')
    await userEvent.type(screen.getByLabelText('密码'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: '登录' }))

    // Inline (form) and transient (toast) surfaces both report the failure.
    await waitFor(() => expect(screen.getAllByText('邮箱或密码不正确。').length).toBe(2))

    await userEvent.click(screen.getByRole('button', { name: '关闭提示' }))
    await waitFor(() => expect(screen.getAllByText('邮箱或密码不正确。').length).toBe(1))
  })

  it('clears a previous error when switching between login and register', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/login') return Promise.reject(new ApiError(401, 'Invalid email or password'))
      return Promise.resolve([])
    })
    renderLogin()
    await waitFor(() => expect(screen.getByRole('heading', { name: '登录' })).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText('邮箱'), 'u@t.dev')
    await userEvent.type(screen.getByLabelText('密码'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: '登录' }))
    await waitFor(() => expect(screen.getAllByText('邮箱或密码不正确。').length).toBe(2))

    // 切换模式只清掉表单里的行内提示；错误 toast 是独立的瞬时通道，
    // 它自己的 assertive live region 也带 role="alert"，所以断言要限定在表单内。
    await userEvent.click(screen.getByRole('button', { name: '去注册' }))
    const form = screen.getByRole('button', { name: '注册' }).closest('form')!
    expect(within(form).queryByRole('alert')).not.toBeInTheDocument()
  })
})
