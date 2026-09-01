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
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ code: 0, msg: '余票不足' }), { status: 200 })))
    const err = (await api('POST', '/api/bookings', {}).catch((e: unknown) => e)) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.message).toBe('余票不足')
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
    expect(formatMoney(123456)).toBe('¥1234.56')
    expect(formatTime('2026-09-10T12:00:00Z')).toContain('2026')
  })
})