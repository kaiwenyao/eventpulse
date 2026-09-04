import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { AlertIcon, CheckIcon, InfoIcon } from './Icons'

export type ToastTone = 'success' | 'error' | 'info'

/** 可选动作：把「报错」变成「下一步做什么」，例如余额不足 → 去充值。 */
export interface ToastAction {
  label: string
  to: string
}

export interface ToastOptions {
  message: string
  tone?: ToastTone
  title?: string
  action?: ToastAction
  /** 毫秒；`null` = 常驻，必须手动关闭。省略则按 tone 取默认值。 */
  duration?: number | null
}

interface Toast {
  id: number
  tone: ToastTone
  title?: string
  message: string
  action?: ToastAction
  duration: number | null
  /** 同一条消息重复出现的次数，渲染成 ×N 而不是叠一摞一样的提示。 */
  count: number
}

interface ToastContextValue {
  notify: (input: string | ToastOptions, tone?: ToastTone) => void
}

const ToastContext = createContext<ToastContextValue>({ notify: () => {} })

/** 成功/提示读完即走；错误需要更久，因为通常还要照着做点什么。 */
const TTL_BY_TONE: Record<ToastTone, number> = {
  success: 4200,
  info: 4200,
  error: 8000,
}

/** 同时最多显示的条数；超出丢弃最旧的，避免 SSE 重连风暴糊满屏幕。 */
const MAX_VISIBLE = 3

const TONE_ICON: Record<ToastTone, typeof CheckIcon> = {
  success: CheckIcon,
  error: AlertIcon,
  info: InfoIcon,
}

function normalise(input: string | ToastOptions, tone?: ToastTone): ToastOptions {
  return typeof input === 'string' ? { message: input, tone } : input
}

/**
 * App-wide transient feedback. Every mutating action reports its outcome here
 * so a failed publish/cancel/check-in is never swallowed silently.
 *
 * 错误与成功走两个独立的 live region：错误是 assertive，读屏用户不会错过；
 * 带动作的错误不自动消失，否则提示会在用户点到「去充值」之前就没了。
 */
export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const nextId = useRef(1)

  const dismiss = useCallback((id: number) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id))
  }, [])

  const notify = useCallback((input: string | ToastOptions, tone?: ToastTone) => {
    const { message, tone: optionTone, title, action, duration } = normalise(input, tone)
    const resolvedTone: ToastTone = optionTone ?? 'info'
    // 带动作的错误常驻：用户需要时间读完并点击。
    const resolvedDuration =
      duration !== undefined ? duration : resolvedTone === 'error' && action ? null : TTL_BY_TONE[resolvedTone]

    setToasts((prev) => {
      const last = prev[prev.length - 1]
      // 重复消息不叠加，只累计次数（同时重置计时，见 ToastRow 的 count 依赖）。
      if (last && last.tone === resolvedTone && last.message === message && last.title === title) {
        return [...prev.slice(0, -1), { ...last, count: last.count + 1 }]
      }
      const next: Toast = {
        id: nextId.current++,
        tone: resolvedTone,
        title,
        message,
        action,
        duration: resolvedDuration,
        count: 1,
      }
      return [...prev, next].slice(-MAX_VISIBLE)
    })
  }, [])

  const value = useMemo(() => ({ notify }), [notify])

  const polite = toasts.filter((toast) => toast.tone !== 'error')
  const assertive = toasts.filter((toast) => toast.tone === 'error')

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="toast-stack">
        {polite.length > 0 && (
          <div className="toast-region" role="status" aria-live="polite">
            {polite.map((toast) => (
              <ToastRow key={toast.id} toast={toast} onDismiss={dismiss} />
            ))}
          </div>
        )}
        {assertive.length > 0 && (
          <div className="toast-region" role="alert" aria-live="assertive">
            {assertive.map((toast) => (
              <ToastRow key={toast.id} toast={toast} onDismiss={dismiss} />
            ))}
          </div>
        )}
      </div>
    </ToastContext.Provider>
  )
}

function ToastRow({ toast, onDismiss }: { toast: Toast; onDismiss: (id: number) => void }) {
  const { t } = useTranslation()
  const [paused, setPaused] = useState(false)
  const Icon = TONE_ICON[toast.tone]
  const { id, duration, count } = toast

  // 悬停 / 聚焦时暂停自动消失；`count` 进依赖，重复消息会重置计时。
  useEffect(() => {
    if (paused || duration === null) return
    const timer = setTimeout(() => onDismiss(id), duration)
    return () => clearTimeout(timer)
  }, [paused, duration, id, count, onDismiss])

  return (
    <div
      className={`toast toast-${toast.tone}`}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
    >
      <span className="toast-mark" aria-hidden />
      <Icon className="toast-icon" />
      <div className="toast-body">
        {toast.title && <p className="toast-title">{toast.title}</p>}
        <p className="toast-message">
          {toast.message}
          {count > 1 && <span className="toast-count">×{count}</span>}
        </p>
        {toast.action && (
          <NavLink className="toast-action" to={toast.action.to} onClick={() => onDismiss(id)}>
            {toast.action.label}
          </NavLink>
        )}
      </div>
      <button type="button" className="toast-close" aria-label={t('common.closeToast')} onClick={() => onDismiss(id)}>
        ×
      </button>
    </div>
  )
}

export function useToast() {
  return useContext(ToastContext)
}
