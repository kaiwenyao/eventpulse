import { afterEach, describe, expect, it, vi } from 'vitest'
import { setAccessToken } from '../api'
import { MAX_BACKOFF_MS, parseReminder, streamBookingEvents } from './sse'

const REMINDER_FRAME =
  'event: reminder\ndata: {"eventId":"evt-1","type":"BOOKING_UPDATED","bookingId":7,"occurredAt":"2026-09-02T10:20:30Z"}\n\n'

function sseResponse(frames: string[], keepOpen = true): Response {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames) controller.enqueue(encoder.encode(frame))
      if (!keepOpen) controller.close()
    },
  })
  return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

describe('parseReminder()', () => {
  it('parses reminder frames and rejects everything else', () => {
    expect(parseReminder(REMINDER_FRAME)).toMatchObject({ eventId: 'evt-1', bookingId: 7 })
    expect(parseReminder('event: ping\ndata: ping\n\n')).toBeNull()
    expect(parseReminder('data: {"eventId":"e","type":"X","bookingId":1}\n\n')).toBeNull()
    expect(parseReminder('event: reminder\ndata: {broken json\n\n')).toBeNull()
    expect(parseReminder('event: reminder\ndata: {"type":"X","bookingId":"seven"}\n\n')).toBeNull()
    expect(parseReminder(': heartbeat comment only\n\n')).toBeNull()
  })
})

describe('streamBookingEvents()', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    setAccessToken(null)
  })

  it('sends the bearer token in the header and delivers reminders', async () => {
    setAccessToken('tok-sse')
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => sseResponse([REMINDER_FRAME]))
    vi.stubGlobal('fetch', fetchMock)
    const reminders: unknown[] = []
    const controller = new AbortController()
    const done = streamBookingEvents(7, (r) => reminders.push(r), controller.signal)
    await vi.waitFor(() => expect(reminders).toHaveLength(1))
    expect(reminders[0]).toMatchObject({ eventId: 'evt-1' })
    // JWT 走 Authorization 头，不进 URL。
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/bookings/7/events')
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer tok-sse')
    controller.abort()
    await expect(done).resolves.toBeUndefined()
  })

  it('reconnects with growing backoff after the stream ends and resets on success', async () => {
    vi.useFakeTimers()
    try {
      const closed = sseResponse([], false) // 服务端关流：触发自动重连
      const alive = sseResponse([REMINDER_FRAME])
      const fetchMock = vi.fn().mockResolvedValueOnce(closed).mockResolvedValue(alive)
      vi.stubGlobal('fetch', fetchMock)
      const reminders: unknown[] = []
      const controller = new AbortController()
      const done = streamBookingEvents(7, (r) => reminders.push(r), controller.signal, 100)
      await vi.advanceTimersByTimeAsync(90)
      expect(fetchMock).toHaveBeenCalledTimes(1)
      // 第一次失败后等待 100ms 再重连。
      await vi.advanceTimersByTimeAsync(120)
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
      expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(false)
      // 重连成功后退避重置：再次断流时等待时间不再翻倍到最大值。
      await vi.waitFor(() => expect(reminders).toHaveLength(1))
      controller.abort()
      await expect(done).resolves.toBeUndefined()
      expect(MAX_BACKOFF_MS).toBe(30000)
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops reconnecting once aborted', async () => {
    const fetchMock = vi.fn(async () => sseResponse([], false))
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()
    const done = streamBookingEvents(7, () => {}, controller.signal, 5)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled())
    controller.abort()
    await expect(done).resolves.toBeUndefined()
    const calls = fetchMock.mock.calls.length
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(fetchMock.mock.calls.length).toBe(calls)
  })

  it('reconnects after an HTTP error status', async () => {
    const failing = new Response('nope', { status: 502 })
    const healthy = sseResponse([REMINDER_FRAME])
    const fetchMock = vi.fn().mockResolvedValueOnce(failing).mockResolvedValue(healthy)
    vi.stubGlobal('fetch', fetchMock)
    const reminders: unknown[] = []
    const controller = new AbortController()
    const done = streamBookingEvents(7, (r) => reminders.push(r), controller.signal, 5)
    await vi.waitFor(() => expect(reminders).toHaveLength(1))
    controller.abort()
    await expect(done).resolves.toBeUndefined()
  })
})
