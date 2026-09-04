import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, formatMoney, formatTime, setAccessToken } from './api'

describe('api()', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    setAccessToken(null)
  })

  it('sends bearer token and returns data', async () => {
    setAccessToken('tok-1')
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ code: 1, data: { ok: 1 } }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(api<{ ok: number }>('POST', '/api/events', { a: 1 })).resolves.toEqual({ ok: 1 })
    expect(JSON.stringify(fetchMock.mock.calls[0])).toContain('Bearer tok-1')
  })

  it('throws ApiError when code is 0', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ code: 0, msg: 'Sold out' }), { status: 200 })))
    const err = (await api('POST', '/api/bookings', {}).catch((e: unknown) => e)) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.message).toBe('Sold out')
  })

  it('falls back when the body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('nope', { status: 500 })))
    const err = (await api('GET', '/api/events').catch((e: unknown) => e)) as ApiError
    expect(err.status).toBe(500)
    expect(err.message).toBe('请求失败')
  })
})

describe('uploadFile()', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    setAccessToken(null)
  })

  it('posts multipart and returns data', async () => {
    setAccessToken('tok-1')
    const { uploadFile } = await import('./api')
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ code: 1, data: { id: 9 } }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(uploadFile<{ id: number }>('/api/media/images', new File(['x'], 'a.png'))).resolves.toEqual({ id: 9 })
  })
})

describe('formatters', () => {
  it('formats money and time', () => {
    expect(formatMoney(123456)).toBe('€1234.56')
    expect(formatTime('2026-09-10T12:00:00Z')).toContain('2026')
  })
})

describe('token storage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    setAccessToken(null)
    localStorage.clear()
    sessionStorage.clear()
  })

  it('persists the token in localStorage so other tabs share the login', () => {
    setAccessToken('tok-1')
    expect(localStorage.getItem('ep_token')).toBe('tok-1')
    expect(sessionStorage.getItem('ep_token')).toBeNull()
    setAccessToken(null)
    expect(localStorage.getItem('ep_token')).toBeNull()
  })

  it('follows login and logout made in another tab via the storage event', async () => {
    setAccessToken('tab-a')
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ code: 1, data: {} }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    // 另一个标签页退出登录：本页下一次请求不再带 Authorization。
    window.dispatchEvent(new StorageEvent('storage', { key: 'ep_token', newValue: null }))
    await api('GET', '/api/events')
    expect(JSON.stringify(fetchMock.mock.calls[0])).not.toContain('Bearer')

    // 另一个标签页登录：本页随后的请求带上新 token。
    window.dispatchEvent(new StorageEvent('storage', { key: 'ep_token', newValue: 'tab-b' }))
    await api('GET', '/api/events')
    expect(JSON.stringify(fetchMock.mock.calls[1])).toContain('Bearer tab-b')
  })

  it('migrates a legacy sessionStorage token into localStorage on load', async () => {
    localStorage.removeItem('ep_token')
    sessionStorage.setItem('ep_token', 'legacy')
    vi.resetModules()
    await import('./api')
    expect(localStorage.getItem('ep_token')).toBe('legacy')
    expect(sessionStorage.getItem('ep_token')).toBeNull()
  })
})