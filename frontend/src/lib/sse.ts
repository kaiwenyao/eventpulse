import { getAccessToken } from '../api'

/**
 * 轻量 SSE 提醒：服务器只发「有变化，请刷新」，业务数据一律重新走 REST。
 * 用 fetch + Authorization 头建立连接（长期 JWT 不进 URL），
 * 断开后逐步延长等待时间自动重连；页面关闭时用 AbortController 主动断开。
 */
export interface BookingReminder {
  eventId: string
  type: string
  /** 订单级提醒的目标订单；用户级提醒（购物车 / 钱包 / 订单列表刷新）没有它。 */
  bookingId: number
  occurredAt: string
}

/** 用户级提醒：只发给所属用户，页面收到后重新拉取自己的数据。 */
export interface UserReminder {
  eventId: string
  type: string
  occurredAt: string
}

export const INITIAL_BACKOFF_MS = 1000
export const MAX_BACKOFF_MS = 30000

/**
 * 解析一个 SSE 帧；只认 name=reminder 的数据帧，坏数据一律忽略。
 * 订单级提醒带 bookingId，用户级提醒带 userId，两者必有其一。
 */
export function parseReminder(frame: string): (BookingReminder | UserReminder) | null {
  let event = ''
  let data = ''
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('data:')) data += line.slice(5).trimStart()
    else if (line.startsWith('event:')) event = line.slice(6).trim()
  }
  if (event !== 'reminder' || !data) return null
  try {
    const parsed = JSON.parse(data) as {
      eventId?: string
      type?: string
      bookingId?: number
      userId?: number
      occurredAt?: string
    }
    if (!parsed.eventId || !parsed.type) return null
    if (typeof parsed.bookingId === 'number') return parsed as BookingReminder
    if (typeof parsed.userId === 'number') return parsed as UserReminder
    return null
  } catch {
    return null
  }
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(finish, ms)
    function finish() {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
    signal.addEventListener('abort', finish)
  })
}

/**
 * 订阅订单事件直到 abort。重连成功后退避时间重置；
 * 每次建连成功（含首次与重连）都会调用 onOpen：提醒是 Redis 广播、不留底，
 * 断线窗口内发生的变化必须由调用方重新拉取 REST 数据补上。
 */
export async function streamBookingEvents(
  bookingId: number,
  onReminder: (reminder: BookingReminder) => void,
  signal: AbortSignal,
  initialBackoffMs: number = INITIAL_BACKOFF_MS,
  onOpen?: () => void,
): Promise<void> {
  return streamEvents(
    `/api/bookings/${bookingId}/events`,
    (reminder) => onReminder(reminder as BookingReminder),
    signal,
    initialBackoffMs,
    onOpen,
  )
}

/**
 * 用户级刷新频道（购物车 / 钱包 / 订单列表）。提醒只说明「有变化」，
 * 页面正确性以重新拉取的 REST 数据为准，丢失的提醒由建连补偿覆盖。
 */
export async function streamUserEvents(
  onReminder: (reminder: UserReminder) => void,
  signal: AbortSignal,
  initialBackoffMs: number = INITIAL_BACKOFF_MS,
  onOpen?: () => void,
): Promise<void> {
  return streamEvents(
    '/api/user/events',
    (reminder) => onReminder(reminder as UserReminder),
    signal,
    initialBackoffMs,
    onOpen,
  )
}

async function streamEvents(
  url: string,
  onReminder: (reminder: BookingReminder | UserReminder) => void,
  signal: AbortSignal,
  initialBackoffMs: number,
  onOpen?: () => void,
): Promise<void> {
  let backoff = initialBackoffMs
  while (!signal.aborted) {
    try {
      const headers: Record<string, string> = { Accept: 'text/event-stream' }
      const token = getAccessToken()
      if (token) headers.Authorization = `Bearer ${token}`
      const response = await fetch(url, { headers, signal })
      if (!response.ok || !response.body) {
        throw new Error(`SSE connection failed (${response.status})`)
      }
      // 连接建立成功：退避重置，并让调用方补偿拉取 REST 数据。
      backoff = initialBackoffMs
      // 回调异常只代表刷新失败，不应断开 SSE 订阅。
      try {
        onOpen?.()
      } catch {
        // ignore
      }
      const reader = response.body.getReader()
      // 页面关闭时主动断开：即使 fetch 实现没有把 abort 传进流，也取消读取。
      const onAbort = () => {
        void reader.cancel().catch(() => {})
      }
      signal.addEventListener('abort', onAbort, { once: true })
      try {
        const decoder = new TextDecoder()
        let buffer = ''
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          let boundary = buffer.indexOf('\n\n')
          while (boundary >= 0) {
            const frame = buffer.slice(0, boundary)
            buffer = buffer.slice(boundary + 2)
            const reminder = parseReminder(frame)
            if (reminder) onReminder(reminder)
            boundary = buffer.indexOf('\n\n')
          }
        }
      }
      finally {
        signal.removeEventListener('abort', onAbort)
      }
    } catch {
      if (signal.aborted) return
    }
    if (signal.aborted) return
    await sleep(backoff, signal)
    backoff = Math.min(backoff * 2, MAX_BACKOFF_MS)
  }
}

// ---- AI 发现助手：一次性流式回答 ----

/**
 * AI 发现助手一次回答的帧载荷。
 * done 帧携带与 /chat 相同的响应结构（活动已由 Spring 复核）。
 */
export interface AiStreamDelta {
  text: string
}

export interface AiStreamDone {
  requestId: string
  conversationId: string | null
  answer: string
  events: { event: import('../types').EventVo; reason: string }[]
  followUpQuestions: string[]
}

export interface AiStreamError {
  message: string
}

export interface AiStreamHandlers {
  onDelta: (text: string) => void
  onDone: (done: AiStreamDone) => void
  onError: (message: string) => void
}

function parseAiFrame(frame: string): { event: string; data: string } {
  let event = ''
  let data = ''
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('data:')) data += line.slice(5).trimStart()
    else if (line.startsWith('event:')) event = line.slice(6).trim()
  }
  return { event, data }
}

/**
 * 发一次流式问题给 AI 发现助手。
 *
 * 与上面的提醒频道不同，这里刻意【不自动重连】：一次回答断了就是断了，
 * 不能把同一个回答从头再答一遍。调用方负责在 error / 异常结束时展示明确
 * 的失败提示，并丢弃半截内容。返回 AbortController 供取消。
 */
export async function streamChatAnswer(
  body: { conversationId: string | null; message: string; locale?: string | null },
  handlers: AiStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
    'Content-Type': 'application/json',
  }
  const token = getAccessToken()
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch('/api/ai/discovery/chat/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    signal,
  })
  // 非 2xx：错误体是 JSON（未钉死 produces 的好处），转成明确失败。
  if (!response.ok) {
    const json = (await response.json().catch(() => ({}))) as { msg?: string }
    handlers.onError(json.msg ?? `Request failed (${response.status})`)
    return
  }
  if (!response.body) {
    handlers.onError('AI assistant is temporarily unavailable')
    return
  }
  const reader = response.body.getReader()
  const onAbort = () => {
    void reader.cancel().catch(() => {})
  }
  signal.addEventListener('abort', onAbort, { once: true })
  try {
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const frame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        const { event, data } = parseAiFrame(frame)
        if (data) {
          try {
            const payload = JSON.parse(data)
            if (event === 'delta') handlers.onDelta(String(payload.text ?? ''))
            else if (event === 'done') handlers.onDone(payload as AiStreamDone)
            else if (event === 'error') handlers.onError(String(payload.message ?? 'AI assistant is temporarily unavailable'))
          } catch {
            // 坏帧忽略；缺 done 时由调用方按「无结果」处理。
          }
        }
        boundary = buffer.indexOf('\n\n')
      }
    }
  } finally {
    signal.removeEventListener('abort', onAbort)
  }
}
