import i18n from './i18n'

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

const TOKEN_KEY = 'ep_token'

// 旧版本把 token 存在 sessionStorage（按标签页隔离），部署后把还留在
// 当前标签页的旧 token 迁进 localStorage，避免已登录用户被登出。
const legacyToken = sessionStorage.getItem(TOKEN_KEY)
if (legacyToken) {
  if (!localStorage.getItem(TOKEN_KEY)) localStorage.setItem(TOKEN_KEY, legacyToken)
  sessionStorage.removeItem(TOKEN_KEY)
}

let accessToken: string | null = localStorage.getItem(TOKEN_KEY)

export function setAccessToken(token: string | null) {
  accessToken = token
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

// storage 事件只在“别的标签页”触发：登录/退出发生在另一标签页时，
// 同步本页内存里的 token，避免旧标签页带着失效状态继续请求。
window.addEventListener('storage', (e) => {
  if (e.key === TOKEN_KEY) accessToken = e.newValue
})

export function getAccessToken() {
  return accessToken
}

export async function api<T>(method: string, path: string, body?: unknown, extraHeaders?: Record<string, string>): Promise<T> {
  const headers: Record<string, string> = { ...extraHeaders }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const json = (await response.json().catch(() => ({}))) as { code?: number; msg?: string; data?: T }
  if (!response.ok || json.code === 0) {
    throw new ApiError(response.status, json.msg ?? i18n.t('common.requestFailed'))
  }
  return json.data as T
}

export function formatMoney(cents: number) {
  // 金额来自网络，字段缺失时 `cents` 可能是 undefined —— 宁可显示 €0.00，
  // 也不要把 "€NaN" 摆到用户面前。
  const safe = Number.isFinite(cents) ? cents : 0
  return `€${(safe / 100).toFixed(2)}`
}

export function formatTime(iso: string) {
  const locale = i18n.language === 'en' ? 'en' : 'zh-CN'
  return new Date(iso).toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' })
}

export async function uploadFile<T>(path: string, file: File): Promise<T> {
  const headers: Record<string, string> = {}
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`
  const body = new FormData()
  body.append('file', file)
  const response = await fetch(path, { method: 'POST', headers, body })
  const json = (await response.json().catch(() => ({}))) as { code?: number; msg?: string; data?: T }
  if (!response.ok || json.code === 0) {
    throw new ApiError(response.status, json.msg ?? i18n.t('common.requestFailed'))
  }
  return json.data as T
}
