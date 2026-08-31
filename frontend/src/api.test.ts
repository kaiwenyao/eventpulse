import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, formatMoney, formatTime, newIdempotencyKey, BOOKING_STATUS_LABEL } from './api'

describe('newIdempotencyKey', () => {
  it('produces >=128-bit url-safe keys with no padding', () => {
    const seen = new Set<string>()
    for (let i = 0; i < 50; i++) {
      const key = newIdempotencyKey()
      // 32 random bytes -> 43 base64url chars without padding
      expect(key).toMatch(/^[A-Za-z0-9_-]{43}$/)
      expect(key.includes('=')).toBe(false)
      seen.add(key)
    }
    expect(seen.size).toBe(50) // CSPRNG: never repeats
  })
})

describe('api()', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('attaches auth, idempotency and reauth headers and parses JSON', async () => {
    setAccessTokenForTest('tok-1')
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response(
      JSON.stringify({ ok: 1 }),
      { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    const data = await api<{ ok: number }>('POST', '/api/v1/x', { a: 1 },
      { idempotencyKey: 'k'.repeat(43), reauthToken: 'r' })

    expect(data).toEqual({ ok: 1 })
    const headers = (fetchMock.mock.calls[0][1]?.headers) as Record<string, string>
    expect(headers['Authorization']).toBe('Bearer tok-1')
    expect(headers['Idempotency-Key']).toBe('k'.repeat(43))
    expect(headers['X-Reauth-Token']).toBe('r')
    expect(headers['Content-Type']).toBe('application/json')
    setAccessTokenForTest(null)
  })

  it('throws ApiError with server code, message and field errors', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ code: 'AGE_REQUIREMENT_NOT_CONFIRMED', message: '年龄限制', fieldErrors: { age: '未确认' } }),
      { status: 422, headers: { 'Content-Type': 'application/json' } })))

    const err = await api('POST', '/api/v1/bookings', {}).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    const apiError = err as ApiError
    expect(apiError.status).toBe(422)
    expect(apiError.code).toBe('AGE_REQUIREMENT_NOT_CONFIRMED')
    expect(apiError.message).toBe('年龄限制')
    expect(apiError.fieldErrors).toEqual({ age: '未确认' })
  })

  it('treats malformed error bodies as UNKNOWN', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('not-json', { status: 500 })))
    const err = await api('GET', '/api/v1/events').catch((e: unknown) => e)
    expect((err as ApiError).code).toBe('UNKNOWN')
  })

  it('maps a 204 response onto an empty object', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 204 })))
    await expect(api('POST', '/api/v1/events/x/publish', {})).resolves.toEqual({})
  })
})

describe('money and time formatting', () => {
  it('keeps money in integer minor units and never floats', () => {
    expect(formatMoney(123456)).toBe('1234.56 CNY')
    expect(formatMoney(5)).toBe('0.05 CNY')
    expect(formatMoney(null)).toBe('—')
    expect(formatMoney(undefined, 'USD')).toBe('—')
  })

  it('shows a dash for missing timestamps and a locale time otherwise', () => {
    expect(formatTime(null)).toBe('—')
    expect(formatTime('2026-09-10T12:00:00Z')).toContain('2026')
  })
})

describe('BOOKING_STATUS_LABEL', () => {
  it('covers every fulfilment state the server can emit', () => {
    for (const status of ['PAYMENT_PENDING', 'CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED',
      'CANCELLED_BEFORE_PAYMENT', 'CANCELLATION_PENDING', 'CANCELLED']) {
      expect(BOOKING_STATUS_LABEL[status]).toBeTruthy()
    }
  })
})

import { setAccessToken } from './api'
function setAccessTokenForTest(token: string | null) {
  setAccessToken(token)
}