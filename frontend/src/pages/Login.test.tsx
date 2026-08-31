import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import Login from './Login'
import { AuthProvider } from '../auth'

const apiMock = vi.hoisted(() => ({ fn: vi.fn() }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    api: apiMock.fn,
    refreshToken: () => apiMock.fn('POST', '/api/v1/auth/refresh', {}),
  }
})

beforeEach(() => apiMock.fn.mockReset())

/** React controls its inputs: the native value setter + input event is what a
 *  real keystroke produces, and the only thing React state observes. */
function typeInto(selector: string, value: string) {
  const el = document.querySelector(selector) as HTMLInputElement
  const setter = Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype, 'value')!.set!
  setter.call(el, value)
  el.dispatchEvent(new Event('input', { bubbles: true }))
}

function renderLogin() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <Login />
      </AuthProvider>
    </MemoryRouter>,
  )
}

const submit = () => fireEvent.submit(document.querySelector('form') as HTMLFormElement)

describe('Login page', () => {
  it('submits login credentials through the auth context', async () => {
    apiMock.fn.mockImplementation(async (_method: string, path: string) => {
      if (path === '/api/v1/auth/login') {
        return { accessToken: 'at', user: { id: 'u', email: 'e@t.dev', role: 'USER', displayName: null } }
      }
      return {} // neutral mount refresh
    })
    renderLogin()
    typeInto('input[type="email"]', 'e@t.dev')
    typeInto('input[type="password"]', 'Smoke!234567890')
    submit()
    // The login POST must carry the exact credentials typed.
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/v1/auth/login',
      expect.objectContaining({ email: 'e@t.dev', password: 'Smoke!234567890' })))
  }, 10_000)

  it('surfaces the server error message on a failed login', async () => {
    const mod = await import('../api')
    apiMock.fn.mockImplementation(async (_method: string, path: string) => {
      if (path === '/api/v1/auth/refresh') {
        return {} // mount stays neutral: a rejection inside act() would leak
      }
      if (path === '/api/v1/auth/login') {
        throw new mod.ApiError(401, 'INVALID_CREDENTIALS', 'invalid credentials')
      }
      return {}
    })
    renderLogin()
    typeInto('input[type="email"]', 'ghost@t.dev')
    typeInto('input[type="password"]', 'Wrong!234567890')
    submit()
    await waitFor(() => expect(screen.getByText('invalid credentials')).toBeTruthy())
  }, 10_000)

  it('switches to the register form and sends displayName', async () => {
    apiMock.fn.mockImplementation(async (_method: string, path: string) => {
      if (path === '/api/v1/auth/refresh') {
        return {} // mount stays neutral
      }
      if (path === '/api/v1/auth/register') {
        return { accessToken: 'at', user: { id: 'u', email: 'n@t.dev', role: 'USER', displayName: 'T' } }
      }
      return {}
    })
    renderLogin()
    fireEvent.click(screen.getByText('去注册'))
    expect(screen.getByText('注册普通用户')).toBeTruthy()
    typeInto('input[type="email"]', 'n@t.dev')
    typeInto('input[type="password"]', 'Smoke!234567890')
    typeInto('input:not([type="password"]):not([type="email"])', 'Tester')
    submit()
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/v1/auth/register',
      expect.objectContaining({ email: 'n@t.dev', displayName: 'Tester' })))
  }, 10_000)
})