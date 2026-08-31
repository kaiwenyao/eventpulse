import { ReactNode } from 'react'
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, useAuth, SessionUser } from './auth'

// Replace ONLY the network edge: the real module keeps setAccessToken state,
// ApiError and formatters; `api` becomes a per-test stub. refreshToken() calls
// the stubbed api('POST', '/api/v1/auth/refresh', {}), so rotate-on-mount and
// login flows are observable without a server.
const apiMock = vi.hoisted(() => ({ fn: vi.fn() }))
vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>()
  return {
    ...actual,
    api: apiMock.fn,
    refreshToken: () => apiMock.fn('POST', '/api/v1/auth/refresh', {}),
  }
})

import { ApiError } from './api'

function wrapper({ children }: { children: ReactNode }) {
  return <AuthProvider>{children}</AuthProvider>
}

const user: SessionUser = { id: 'u1', email: 'u@t.dev', role: 'USER', displayName: 'U' }

beforeEach(() => {
  apiMock.fn.mockReset()
  // Default: unreachable server -> api() throws like the real one would.
  apiMock.fn.mockRejectedValue(new ApiError(500, 'INTERNAL', 'network down'))
})

describe('AuthProvider session restore', () => {
  it('rotates the refresh cookie on mount and keeps the user', async () => {
    apiMock.fn.mockResolvedValueOnce({ accessToken: 'at-1', user })
    const { result } = renderHook(() => useAuth(), { wrapper })

    await waitFor(() => expect(result.current.ready).toBe(true))
    expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/v1/auth/refresh', expect.anything())
    expect(result.current.user).toEqual(user)
  })

  it('clears the session when the cookie is missing or failed, but still becomes ready', async () => {
    apiMock.fn.mockRejectedValueOnce(new ApiError(401, 'UNAUTHENTICATED', 'missing'))
    const { result } = renderHook(() => useAuth(), { wrapper })

    await waitFor(() => expect(result.current.ready).toBe(true))
    expect(result.current.user).toBeNull()
  })
})

describe('AuthProvider login / register / logout', () => {
  it('login stores the access token and user', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/v1/auth/login') {
        return Promise.resolve({ accessToken: 'at', user })
      }
      return Promise.resolve({})
    })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    await act(async () => { await result.current.login('u@t.dev', 'pw-123456789') })
    expect(result.current.user?.email).toBe('u@t.dev')
  })

  it('failed login surfaces the error and keeps the user empty', async () => {
    apiMock.fn.mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', 'invalid credentials'))
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    await expect(result.current.login('u@t.dev', 'wrong-password')).rejects.toThrow('invalid credentials')
    expect(result.current.user).toBeNull()
  })

  it('register uses the register endpoint with the display name', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/v1/auth/register') {
        return Promise.resolve({ accessToken: 'at', user })
      }
      return Promise.resolve({})
    })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    await act(async () => { await result.current.register('n@t.dev', 'pw-123456789', 'Tester') })
    expect(result.current.user?.id).toBe('u1')
    expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/v1/auth/register',
      expect.objectContaining({ email: 'n@t.dev', displayName: 'Tester' }))
  })

  it('logout clears the user even when the server call fails', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/v1/auth/login') {
        return Promise.resolve({ accessToken: 'at', user })
      }
      return Promise.reject(new ApiError(500, 'INTERNAL', 'boom'))
    })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    await act(async () => { await result.current.login('u@t.dev', 'pw-123456789') })
    await act(async () => { await result.current.logout() })
    expect(result.current.user).toBeNull()
  })
})