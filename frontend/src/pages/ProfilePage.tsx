import { FormEvent, useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { api, ApiError, formatMoney } from '../api'
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
 */
export function ProfilePage() {
  const { notify } = useToast()
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [loading, setLoading] = useState(true)
  const [amount, setAmount] = useState('100')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    api<UserProfile>('GET', '/api/auth/profile')
      .then(setProfile)
      .catch(() => setProfile(null))
      .finally(() => setLoading(false))
  }, [])

  async function recharge(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const updated = await api<UserProfile>('POST', '/api/auth/wallet/recharge', { amountCents: Number(amount) * 100 })
      setProfile(updated)
      notify(`充值成功，余额 ${formatMoney(updated.walletCents)}`, 'success')
    } catch (err) {
      const message = err instanceof ApiError ? err.message : '充值失败'
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
            <h1>个人中心</h1>
            <p className="muted">登录后即可查看余额与账户统计。</p>
          </div>
        </header>
        <div className="empty">
          <div className="empty-dot" aria-hidden />
          <p className="empty-title">加载失败</p>
          <p className="muted">暂时无法获取个人资料，请稍后重试。</p>
          <div className="empty-action">
            <NavLink to="/" className="btn-primary btn-link">
              回到首页
            </NavLink>
          </div>
        </div>
      </div>
    )
  }

  const quickAmounts = [50, 100, 200, 500]

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>个人中心</h1>
          <p className="muted">你的账户、余额与活动足迹都在这里。</p>
        </div>
      </header>

      <section className="card profile-hero">
        <div className="profile-avatar" aria-hidden>
          {initials(profile.name, profile.email)}
        </div>
        <div className="profile-copy">
          <h2>{profile.name}</h2>
          <p className="muted">{profile.email}</p>
          <p className="muted small">
            角色：{profile.role === 'ORGANISER' ? '主办方' : '观众'}
          </p>
        </div>
        <div className="profile-balance">
          <span className="muted small">钱包余额</span>
          <strong className="balance-num">{formatMoney(profile.walletCents)}</strong>
          <span className="muted small">累计消费 {formatMoney(profile.totalSpentCents)}</span>
        </div>
      </section>

      <section className="section-head">
        <h2 className="section-title">账户统计</h2>
      </section>

      <div className="stats-grid">
        <NavLink to="/bookings" className="stat-card">
          <strong>{profile.bookingCount}</strong>
          <span>订单</span>
        </NavLink>
        <NavLink to="/bookings" className="stat-card">
          <strong>{profile.ticketCount}</strong>
          <span>电子票</span>
        </NavLink>
        <NavLink to="/favourites" className="stat-card">
          <strong>{profile.favouriteCount}</strong>
          <span>收藏</span>
        </NavLink>
        <NavLink to="/notifications" className="stat-card">
          <strong>{profile.notificationCount}</strong>
          <span>消息</span>
        </NavLink>
      </div>

      <section className="section-head section-title">钱包充值</section>

      <form className="card recharge-box" onSubmit={recharge}>
        <div className="field recharge-field">
          <label htmlFor="recharge-amount">充值金额（元）</label>
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
        <div className="row quick-chips" role="group" aria-label="快捷充值金额">
          {quickAmounts.map((v) => (
            <button
              key={v}
              type="button"
              className={`chip ${Number(amount) === v ? 'active' : ''}`}
              onClick={() => setAmount(String(v))}
            >
              ¥{v}
            </button>
          ))}
        </div>
        <ErrorNote message={error} />
        <button type="submit" className="btn-primary" disabled={busy}>
          {busy ? '充值中…' : '充值'}
        </button>
        <p className="muted small recharge-hint">演示功能：充值不接入真实支付渠道；余额可用于站内预订。</p>
      </form>
    </div>
  )
}
