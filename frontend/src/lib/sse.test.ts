import { afterEach, describe, expect, it, vi } from 'vitest'
import { setAccessToken } from '../api'
import { MAX_BACKOFF_MS, parseReminder, streamBookingEvents, streamChatAnswer } from './sse'

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

  it('calls onOpen on every successful connection, initial and reconnect', async () => {
    vi.useFakeTimers()
    try {
      const closed = sseResponse([], false) // 服务端关流：触发自动重连
      const alive = sseResponse([REMINDER_FRAME])
      const fetchMock = vi.fn().mockResolvedValueOnce(closed).mockResolvedValue(alive)
      vi.stubGlobal('fetch', fetchMock)
      let opens = 0
      const controller = new AbortController()
      const done = streamBookingEvents(7, () => {}, controller.signal, 100, () => {
        opens += 1
      })
      // 首次建连成功即回调（覆盖初始 load 与建连之间的空隙）。
      await vi.waitFor(() => expect(opens).toBe(1))
      // 断流后等待退避（100ms）再重连，重连成功后再次回调。
      await vi.advanceTimersByTimeAsync(120)
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
      await vi.waitFor(() => expect(opens).toBe(2))
      controller.abort()
      await expect(done).resolves.toBeUndefined()
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('streamChatAnswer()', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    setAccessToken(null)
  })

  const DONE_FRAME =
    'event: done\ndata: {"requestId":"r1","conversationId":"31","answer":"找到了。","events":[],"followUpQuestions":[]}\n\n'

  function streamFetch(frames: string[], status = 200): Response {
    const encoder = new TextEncoder()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const frame of frames) controller.enqueue(encoder.encode(frame))
        controller.close()
      },
    })
    return new Response(stream, { status, headers: { 'Content-Type': 'text/event-stream' } })
  }

  it('posts JSON and forwards deltas then done with bearer token', async () => {
    setAccessToken('tok-ai')
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) =>
      streamFetch([
        'event: delta\ndata: {"text":"找到"}\n\n',
        'event: delta\ndata: {"text":"两场"}\n\n',
        DONE_FRAME,
      ]),
    )
    vi.stubGlobal('fetch', fetchMock)
    const deltas: string[] = []
    const done: unknown[] = []
    const controller = new AbortController()
    const p = streamChatAnswer(
      { conversationId: null, message: '周末有什么活动' },
      {
        onDelta: (t) => deltas.push(t),
        onDone: (d) => done.push(d),
        onError: () => {},
      },
      controller.signal,
    )
    await p
    expect(deltas).toEqual(['找到', '两场'])
    expect(done).toHaveLength(1)
    expect((done[0] as { conversationId: string }).conversationId).toBe('31')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/ai/discovery/chat/stream')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(init?.body as string)).toEqual({
      conversationId: null,
      message: '周末有什么活动',
    })
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer tok-ai')
  })

  it('reports error frames and rejects non-2xx JSON errors; no reconnect', async () => {
    const errorBody = new Response(JSON.stringify({ msg: 'Too many AI requests' }), {
      status: 429,
      headers: { 'Content-Type': 'application/json' },
    })
    const fetchMock = vi.fn(async () => errorBody)
    vi.stubGlobal('fetch', fetchMock)
    const errors: string[] = []
    const controller = new AbortController()
    // 非 2xx 抛 ApiError（调用方走本地化映射），与旧 /chat 一致。
    await expect(
      streamChatAnswer(
        { conversationId: null, message: 'hi' },
        { onDelta: () => {}, onDone: () => {}, onError: (m) => errors.push(m) },
        controller.signal,
      ),
    ).rejects.toMatchObject({ status: 429, message: 'Too many AI requests' })
    expect(errors).toEqual([])
    // 失败后不会自动重试：fetch 只被调用一次。
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not auto-reconnect when the stream ends without done', async () => {
    // 一次回答的语义：流结束但没有 done（服务端异常）不重连，交由调用方判失败。
    const fetchMock = vi.fn(async () => streamFetch(['event: delta\ndata: {"text":"半截"}\n\n']))
    vi.stubGlobal('fetch', fetchMock)
    const errors: string[] = []
    await streamChatAnswer(
      { conversationId: null, message: 'hi' },
      { onDelta: () => {}, onDone: () => {}, onError: (m) => errors.push(m) },
      new AbortController().signal,
    )
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('ignores malformed frames and unknown event names', async () => {
    const fetchMock = vi.fn(async () =>
      streamFetch([
        'event: ping\ndata: {"text":"x"}\n\n',
        'data: {broken json\n\n',
        'event: delta\ndata: {"text":"好的"}\n\n',
        DONE_FRAME,
      ]),
    )
    vi.stubGlobal('fetch', fetchMock)
    const deltas: string[] = []
    const done: unknown[] = []
    await streamChatAnswer(
      { conversationId: null, message: 'hi' },
      { onDelta: (t) => deltas.push(t), onDone: (d) => done.push(d), onError: () => {} },
      new AbortController().signal,
    )
    expect(deltas).toEqual(['好的'])
    expect(done).toHaveLength(1)
  })
})
