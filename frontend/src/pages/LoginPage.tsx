import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'
import { EventVo } from '../types'
import { Field, fieldAria } from '../ui/Field'
import { ErrorNote } from '../ui/Badges'
import { useToast } from '../ui/Toast'

/**
 * The two accounts `DemoDataSeeder` creates under the `demo` profile, also
 * printed in the README. They are fixtures for a throwaway local stack, not
 * credentials — hence the scanner annotations.
 */
const DEMO_ACCOUNTS = [
  { label: '观众', email: 'user@eventpulse.dev', password: 'User123456' }, // gitleaks:allow
  { label: '主办方', email: 'organiser@eventpulse.dev', password: 'Organiser123456' }, // gitleaks:allow
]

export function LoginPage() {
  const { login, register } = useAuth()
  const navigate = useNavigate()
  const { notify } = useToast()
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [events, setEvents] = useState<EventVo[]>([])

  // The panel's two figures are real: the public catalogue is readable without
  // a session, so a signed-out visitor sees the live counts, not placeholders.
  useEffect(() => {
    let cancelled = false
    api<EventVo[]>('GET', '/api/events')
      .then((data) => {
        if (!cancelled) setEvents(Array.isArray(data) ? data : [])
      })
      .catch(() => {
        if (!cancelled) setEvents([])
      })
    return () => {
      cancelled = true
    }
  }, [])

  const cityCount = useMemo(() => new Set(events.map((e) => e.city).filter(Boolean)).size, [events])

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      if (mode === 'login') await login(email, password)
      else await register(email, password, name)
      notify(mode === 'login' ? '登录成功' : '注册成功，欢迎加入', 'success')
      navigate('/')
    } catch (err) {
      const message = err instanceof ApiError ? err.message : '失败'
      setError(message)
      notify(message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-shell">
      {/* The editorial half: a solid accent field carrying the pitch, so the
          form column stays a plain working surface. */}
      <aside className="auth-aside">
        <p className="auth-aside-brand">EventPulse</p>
        <h2>继续预订你收藏的城市现场</h2>
        <p className="auth-aside-sub">收藏、订单、电子票与提醒都跟着账号走。</p>
        <dl className="auth-aside-stats">
          <div>
            <dt>{events.length}</dt>
            <dd>在售活动</dd>
          </div>
          <div>
            <dt>{cityCount}</dt>
            <dd>覆盖城市</dd>
          </div>
        </dl>
      </aside>

      <form className="auth-card" onSubmit={onSubmit}>
        <h1>{mode === 'login' ? '登录' : '注册'}</h1>
        <p className="muted auth-sub">
          {mode === 'login' ? '继续预订你收藏的城市现场。' : '创建账号，第一时间抢到好位置。'}
        </p>

        <Field id="auth-email" label="邮箱" required>
          <input {...fieldAria('auth-email')} type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </Field>
        <Field id="auth-password" label="密码" required hint={mode === 'register' ? '至少 8 位，包含字母与数字。' : undefined}>
          <input
            {...fieldAria('auth-password', undefined, mode === 'register' ? 'hint' : undefined)}
            type="password"
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </Field>
        {mode === 'register' && (
          <Field id="auth-name" label="昵称" required>
            <input {...fieldAria('auth-name')} value={name} onChange={(e) => setName(e.target.value)} required />
          </Field>
        )}

        <ErrorNote message={error} />

        <button type="submit" className="btn-primary btn-block" disabled={busy}>
          {busy ? '处理中…' : mode === 'login' ? '登录' : '注册'}
        </button>
        <button
          type="button"
          className="btn-secondary btn-block"
          onClick={() => {
            setMode(mode === 'login' ? 'register' : 'login')
            setError('')
          }}
        >
          {mode === 'login' ? '去注册' : '去登录'}
        </button>

        <div className="demo-block">
          <p className="muted demo-hint">演示账号（点击自动填充）</p>
          <div className="chips chips-loose">
            {DEMO_ACCOUNTS.map((account) => (
              <button
                key={account.email}
                type="button"
                className="chip"
                onClick={() => {
                  setEmail(account.email)
                  setPassword(account.password)
                  setMode('login')
                }}
              >
                {account.label}
              </button>
            ))}
          </div>
        </div>
      </form>
    </div>
  )
}
