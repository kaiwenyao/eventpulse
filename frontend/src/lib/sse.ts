import { getAccessToken } from '../api'

/**
 * 轻量 SSE 提醒：服务器只发「有变化，请刷新」，业务数据一律重新走 REST。
 * 用 fetch + Authorization 头建立连接（长期 JWT 不进 URL），
 * 断开后逐步延长等待时间自动重连；页面关闭时用 AbortController 主动断开。
 */
export interface BookingReminder {
  eventId: string
  type: string
  bookingId: number
  occurredAt: string
}

export const INITIAL_BACKOFF_MS = 1000
export const MAX_BACKOFF_MS = 30000

/** 解析一个 SSE 帧；只认 name=reminder 的数据帧，坏数据一律忽略。 */
export function parseReminder(frame: string): BookingReminder | null {
  let event = ''
  let data = ''
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('data:')) data += line.slice(5).trimStart()
    else if (line.startsWith('event:')) event = line.slice(6).trim()
  }
  if (event !== 'reminder' || !data) return null
  try {
    const parsed = JSON.parse(data) as BookingReminder
    if (typeof parsed.bookingId !== 'number' || !parsed.eventId || !parsed.type) return null
    return parsed
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
  let backoff = initialBackoffMs
  while (!signal.aborted) {
    try {
      const headers: Record<string, string> = { Accept: 'text/event-stream' }
      const token = getAccessToken()
      if (token) headers.Authorization = `Bearer ${token}`
      const response = await fetch(`/api/bookings/${bookingId}/events`, { headers, signal })
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
