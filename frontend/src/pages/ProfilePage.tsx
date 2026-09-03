import { FormEvent, useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { api, ApiError, formatMoney } from '../api'
import { streamUserEvents, INITIAL_BACKOFF_MS } from '../lib/sse'
import { UserProfile } from '../types'
import { ErrorNote } from '../ui/Badges'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'

function initials(name?: string, email?: string) {
  const source = name?.trim() || email?.trim() || '?'
  return source.slice(0, 1).toUpperCase()
}

/**
 * 个人中心：基本信息、钱包余额、充值，以及账户里各维度的统计。
 * 充值仍为演示功能，不接真实支付渠道；余额可用于站内预订与退款。
 * 余额区域提供「余额明细」入口；充值带 Idempotency-Key，重试不会重复入账。
 */
export function ProfilePage() {
  const { t } = useTranslation()
  const { notify } = useToast()
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [loading, setLoading] = useState(true)
  const [amount, setAmount] = useState('100')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<UserProfile>('GET', '/api/auth/profile')
      .then(setProfile)
      .catch(() => setProfile(null))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    load()
    // 充值 / 扣款 / 退款发生在其他页面或设备时，钱包余额在这里同步刷新。
    const controller = new AbortController()
    void streamUserEvents(load, controller.signal, INITIAL_BACKOFF_MS, load)
    return () => controller.abort()
  }, [load])

  async function recharge(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const updated = await api<UserProfile>(
        'POST',
        '/api/auth/wallet/recharge',
        { amountCents: Number(amount) * 100 },
        { 'Idempotency-Key': crypto.randomUUID() },
      )
      setProfile(updated)
      notify(t('profile.rechargeOk', { amount: formatMoney(updated.walletCents) }), 'success')
    } catch (err) {
      const message = err instanceof ApiError ? err.message : t('profile.rechargeFailed')
      setError(message)
      notify(message, 'error')
    } finally {
      setBusy(false)
    }
  }

  if (loading) {
    return (
      <div className="page">
        <SkeletonCard />
      </div>
    )
  }

  if (!profile) {
    return (
      <div className="page">
        <header className="page-head">
          <div>
            <h1>{t('profile.title')}</h1>
            <p className="muted">{t('profile.needLogin')}</p>
          </div>
        </header>
        <div className="empty">
          <div className="empty-dot" aria-hidden />
          <p className="empty-title">{t('common.loadFailed')}</p>
          <p className="muted">{t('profile.loadHint')}</p>
          <div className="empty-action">
            <NavLink to="/" className="btn-primary btn-link">
              {t('profile.home')}
            </NavLink>
          </div>
        </div>
      </div>
    )
  }

  const quickAmounts = [50, 100, 200, 500]
  const roleLabel = profile.role === 'ORGANISER' ? t('profile.roleOrganiser') : t('profile.roleAudience')

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>{t('profile.title')}</h1>
          <p className="muted">{t('profile.sub')}</p>
        </div>
      </header>

      <section className="card profile-hero">
        <div className="profile-avatar" aria-hidden>
          {initials(profile.name, profile.email)}
        </div>
        <div className="profile-copy">
          <h2>{profile.name}</h2>
          <p className="muted">{profile.email}</p>
          <p className="muted small">{t('profile.role', { role: roleLabel })}</p>
        </div>
        <div className="profile-balance">
          <span className="muted small">{t('profile.wallet')}</span>
          <strong className="balance-num">{formatMoney(profile.walletCents)}</strong>
          <span className="muted small">{t('profile.spent', { amount: formatMoney(profile.totalSpentCents) })}</span>
          <NavLink to="/wallet/ledger" className="btn-ghost btn-sm ledger-link">
            {t('profile.ledgerLink')}
          </NavLink>
        </div>
      </section>

      <section className="section-head">
        <h2 className="section-title">{t('profile.stats')}</h2>
      </section>

      <div className="stats-grid">
        <NavLink to="/bookings" className="stat-card">
          <strong>{profile.bookingCount}</strong>
          <span>{t('profile.orders')}</span>
        </NavLink>
        <NavLink to="/bookings" className="stat-card">
          <strong>{profile.ticketCount}</strong>
          <span>{t('profile.tickets')}</span>
        </NavLink>
        <NavLink to="/favourites" className="stat-card">
          <strong>{profile.favouriteCount}</strong>
          <span>{t('profile.favourites')}</span>
        </NavLink>
        <NavLink to="/notifications" className="stat-card">
          <strong>{profile.notificationCount}</strong>
          <span>{t('profile.messages')}</span>
        </NavLink>
      </div>

      <section className="section-head section-title">{t('profile.recharge')}</section>

      <form className="card recharge-box" onSubmit={recharge}>
        <div className="field recharge-field">
          <label htmlFor="recharge-amount">{t('profile.amount')}</label>
          <input
            id="recharge-amount"
            type="number"
            min={1}
            max={5000}
            step={1}
            inputMode="numeric"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            required
          />
        </div>
        <div className="row quick-chips" role="group" aria-label={t('profile.quickAria')}>
          {quickAmounts.map((v) => (
            <button
              key={v}
              type="button"
              className={`chip ${Number(amount) === v ? 'active' : ''}`}
              onClick={() => setAmount(String(v))}
            >
              €{v}
            </button>
          ))}
        </div>
        <ErrorNote message={error} />
        <button type="submit" className="btn-primary" disabled={busy}>
          {busy ? t('profile.recharging') : t('profile.doRecharge')}
        </button>
        <p className="muted small recharge-hint">{t('profile.hint')}</p>
      </form>
    </div>
  )
}
