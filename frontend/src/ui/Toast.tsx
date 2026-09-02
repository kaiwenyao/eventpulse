import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

export type ToastTone = 'success' | 'error' | 'info'

interface Toast {
  id: number
  tone: ToastTone
  message: string
}

interface ToastContextValue {
  notify: (message: string, tone?: ToastTone) => void
}

const ToastContext = createContext<ToastContextValue>({ notify: () => {} })

const TOAST_TTL_MS = 4200

/**
 * App-wide transient feedback. Every mutating action reports its outcome here
 * so a failed publish/cancel/check-in is never swallowed silently.
 */
export function ToastProvider({ children }: { children: React.ReactNode }) {
  const { t } = useTranslation()
  const [toasts, setToasts] = useState<Toast[]>([])
  const nextId = useRef(1)
  const timers = useRef<ReturnType<typeof setTimeout>[]>([])

  // Auto-dismiss timers must not outlive the provider.
  useEffect(() => () => timers.current.forEach(clearTimeout), [])

  const dismiss = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }, [])

  const notify = useCallback(
    (message: string, tone: ToastTone = 'info') => {
      const id = nextId.current++
      setToasts((prev) => [...prev, { id, tone, message }])
      timers.current.push(setTimeout(() => dismiss(id), TOAST_TTL_MS))
    },
    [dismiss],
  )

  const value = useMemo(() => ({ notify }), [notify])

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="toast-stack" role="status" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast toast-${toast.tone}`}>
            <span className="toast-mark" aria-hidden />
            <p>{toast.message}</p>
            <button type="button" className="toast-close" aria-label={t('common.closeToast')} onClick={() => dismiss(toast.id)}>
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  return useContext(ToastContext)
}
