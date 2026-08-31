export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

let accessToken: string | null = sessionStorage.getItem('ep_token')

export function setAccessToken(token: string | null) {
  accessToken = token
  if (token) sessionStorage.setItem('ep_token', token)
  else sessionStorage.removeItem('ep_token')
}

export function getAccessToken() {
  return accessToken
}

export async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const json = (await response.json().catch(() => ({}))) as { code?: number; msg?: string; data?: T }
  if (!response.ok || json.code === 0) {
    throw new ApiError(response.status, json.msg ?? '请求失败')
  }
  return json.data as T
}

export function formatMoney(cents: number) {
  return `¥${(cents / 100).toFixed(2)}`
}

export function formatTime(iso: string) {
  return new Date(iso).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
}
