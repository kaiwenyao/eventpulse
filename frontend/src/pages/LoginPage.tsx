import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { EventVo } from '../types'
import { Field, fieldAria } from '../ui/Field'
import { ErrorNote } from '../ui/Badges'
import { useToast } from '../ui/Toast'
import { resolveApiError } from '../lib/apiError'

/**
 * The two accounts `DemoDataSeeder` creates under the `demo` profile, also
 * printed in the README. They are fixtures for a throwaway local stack, not
 * credentials — hence the scanner annotations.
 */
const DEMO_ACCOUNTS = [
  { key: 'login.audience' as const, email: 'user@eventpulse.dev', password: 'User123456' }, // gitleaks:allow
  { key: 'login.organiser' as const, email: 'organiser@eventpulse.dev', password: 'Organiser123456' }, // gitleaks:allow
]

export function LoginPage() {
  const { t } = useTranslation()
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
      notify(mode === 'login' ? t('login.successLogin') : t('login.successRegister'), 'success')
      navigate('/')
    } catch (err) {
      const { message, action } = resolveApiError(err, 'common.failed')
      setError(message)
      notify({ message, action, tone: 'error' })
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
        <h2>{t('login.asideTitle')}</h2>
        <p className="auth-aside-sub">{t('login.asideSub')}</p>
        <dl className="auth-aside-stats">
          <div>
            <dt>{events.length}</dt>
            <dd>{t('login.onSale')}</dd>
          </div>
          <div>
            <dt>{cityCount}</dt>
            <dd>{t('events.cities')}</dd>
          </div>
        </dl>
      </aside>

      <form className="auth-card" onSubmit={onSubmit}>
        <h1>{mode === 'login' ? t('login.headingLogin') : t('login.headingRegister')}</h1>
        <p className="muted auth-sub">
          {mode === 'login' ? t('login.subLogin') : t('login.subRegister')}
        </p>

        <Field id="auth-email" label={t('login.email')} required>
          <input {...fieldAria('auth-email')} type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </Field>
        <Field id="auth-password" label={t('login.password')} required hint={mode === 'register' ? t('login.passwordHint') : undefined}>
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
          <Field id="auth-name" label={t('login.name')} required>
            <input {...fieldAria('auth-name')} value={name} onChange={(e) => setName(e.target.value)} required />
          </Field>
        )}

        <ErrorNote message={error} />

        <button type="submit" className="btn-primary btn-block" disabled={busy}>
          {busy ? t('common.processing') : mode === 'login' ? t('login.headingLogin') : t('login.headingRegister')}
        </button>
        <button
          type="button"
          className="btn-secondary btn-block"
          onClick={() => {
            setMode(mode === 'login' ? 'register' : 'login')
            setError('')
          }}
        >
          {mode === 'login' ? t('login.goRegister') : t('login.goLogin')}
        </button>

        <div className="demo-block">
          <p className="muted demo-hint">{t('login.demoHint')}</p>
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
                {t(account.key)}
              </button>
            ))}
          </div>
        </div>
      </form>
    </div>
  )
}
