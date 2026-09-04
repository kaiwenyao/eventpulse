import { NavLink } from 'react-router-dom'
import { AlertIcon, CheckIcon, InfoIcon } from './Icons'

export type AlertTone = 'error' | 'warn' | 'info' | 'success'

interface AlertProps {
  tone?: AlertTone
  title?: string
  /** 可选跳转动作，例如余额不足 → 去充值。 */
  action?: { label: string; to: string }
  children: React.ReactNode
}

const TONE_ICON: Record<AlertTone, typeof CheckIcon> = {
  error: AlertIcon,
  warn: AlertIcon,
  info: InfoIcon,
  success: CheckIcon,
}

/**
 * 行内提示条。复用设计系统里已有的 `.callout` 词汇（主办方后台在用），
 * 让页面内提示与 toast 是同一套语言：图标 + 左侧竖条 + 可选标题 + 可选动作。
 *
 * 错误与警告带 `role="alert"`，读屏会主动播报；info / success 是被动信息，
 * 保持静默以免打断当前朗读。
 */
export function Alert({ tone = 'info', title, action, children }: AlertProps) {
  const Icon = TONE_ICON[tone]
  const isLoud = tone === 'error' || tone === 'warn'
  return (
    <div className={`callout callout-${tone}`} role={isLoud ? 'alert' : undefined}>
      <Icon className="callout-icon" />
      <div className="callout-body">
        {title && <p className="callout-title">{title}</p>}
        {typeof children === 'string' ? <p>{children}</p> : children}
        {action && (
          <div className="callout-actions">
            <NavLink className="btn-secondary btn-sm btn-link" to={action.to}>
              {action.label}
            </NavLink>
          </div>
        )}
      </div>
    </div>
  )
}
