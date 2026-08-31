/**
 * Typed API boundary. Auth credentials travel via HttpOnly refresh cookie +
 * in-memory access token; ticket raw tokens never touch localStorage or
 * analytics. Money is handled in integer minor units only.
 */

export class ApiError extends Error {
  code: string
  status: number
  fieldErrors: Record<string, string>

  constructor(status: number, code: string, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

let accessToken: string | null = null

export function setAccessToken(token: string | null) {
  accessToken = token
}

/** >= 128-bit CSPRNG idempotency key, base64url. */
export function newIdempotencyKey(): string {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export async function api<T = Record<string, unknown>>(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
  options: { idempotencyKey?: string; reauthToken?: string; ifMatch?: string } = {},
): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey
  if (options.reauthToken) headers['X-Reauth-Token'] = options.reauthToken
  if (options.ifMatch) headers['If-Match'] = options.ifMatch
  const response = await fetch(path, {
    method,
    headers,
    credentials: 'include',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (response.status === 204) return {} as T
  const data = (await response.json().catch(() => ({}))) as Record<string, unknown>
  if (!response.ok) {
    throw new ApiError(
      response.status,
      String(data['code'] ?? 'UNKNOWN'),
      String(data['message'] ?? '请求失败'),
      (data['fieldErrors'] as Record<string, string>) ?? {},
    )
  }
  return data as T
}

export function refreshToken(): Promise<{ accessToken: string; user: { id: string; email: string; role: string; displayName: string | null } }> {
  return api('POST', '/api/v1/auth/refresh', {})
}

export function formatMoney(minor: number | null | undefined, currency = 'CNY'): string {
  if (minor == null) return '—'
  return `${(minor / 100).toFixed(2)} ${currency}`
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
}

export const BOOKING_STATUS_LABEL: Record<string, string> = {
  PAYMENT_PENDING: '待支付',
  CONFIRMED: '已确认',
  PAYMENT_FAILED: '支付失败',
  EXPIRED: '已超时',
  CANCELLED_BEFORE_PAYMENT: '已取消（支付前）',
  CANCELLATION_PENDING: '取消处理中（退款）',
  CANCELLED: '已取消',
}
