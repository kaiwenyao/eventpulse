import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, useNavigate } from 'react-router-dom'
import { api, formatMoney, formatTime } from '../api'
import { resolveApiError } from '../lib/apiError'
import { streamUserEvents, INITIAL_BACKOFF_MS } from '../lib/sse'
import { CartVo, CheckoutVo, UserProfile } from '../types'
import { Alert } from '../ui/Alert'
import { EmptyState, ErrorNote, EventStatusBadge } from '../ui/Badges'
import { ConfirmDialog } from '../ui/Modal'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'

/**
 * 购物车：数据持久化在后端（换设备 / 重新登录仍在），这里只负责交互。
 * 金额是展示值；结算时后端重新校验活动、库存、数量与价格。
 *
 * 幂等：进入结算时生成一次 Idempotency-Key 并保存在 sessionStorage，
 * 网络失败重试 / 重复点击复用同一个键 —— 服务端保证同一结算只执行一次。
 * 结算成功后键被清除，下一次结算是一笔新交易。
 */
const CHECKOUT_KEY_STORAGE = 'ep_checkout_key'

function newCheckoutKey(): string {
  const existing = sessionStorage.getItem(CHECKOUT_KEY_STORAGE)
  if (existing) return existing
  const key = crypto.randomUUID()
  sessionStorage.setItem(CHECKOUT_KEY_STORAGE, key)
  return key
}

function clearCheckoutKey() {
  sessionStorage.removeItem(CHECKOUT_KEY_STORAGE)
}

export function CartPage() {
  const { t } = useTranslation()
  const { notify } = useToast()
  const navigate = useNavigate()
  const [cart, setCart] = useState<CartVo | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [busyItem, setBusyItem] = useState<number | null>(null)
  const [checkingOut, setCheckingOut] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)
  const [priceConfirm, setPriceConfirm] = useState(false)
  const [profile, setProfile] = useState<UserProfile | null>(null)

  const load = useCallback(() => {
    api<CartVo>('GET', '/api/cart')
      .then((data) => {
        setCart(data)
        setLoadError('')
      })
      .catch((e) => {
        setLoadError(resolveApiError(e, 'cart.loadFailed').message)
      })
      .finally(() => setLoading(false))
  }, [])

  // 钱包余额用于结算前预检：让「钱不够」在点按钮之前就可见，而不是变成一条报错。
  const loadProfile = useCallback(() => {
    api<UserProfile>('GET', '/api/auth/profile')
      .then(setProfile)
      .catch(() => setProfile(null))
  }, [])

  useEffect(() => {
    load()
    loadProfile()
    // 其他页面 / 设备改了购物车（或结算完成）时收到提醒 → 重新拉取。
    const controller = new AbortController()
    const refresh = () => {
      load()
      loadProfile()
    }
    void streamUserEvents(refresh, controller.signal, INITIAL_BACKOFF_MS, refresh)
    return () => controller.abort()
  }, [load, loadProfile])

  async function mutate(action: () => Promise<CartVo>, itemId: number | null = null) {
    setBusyItem(itemId)
    try {
      setCart(await action())
    } catch (e) {
      const { message, action } = resolveApiError(e, 'common.operationFailed')
      notify({ message, action, tone: 'error' })
      load()
    } finally {
      setBusyItem(null)
    }
  }

  async function checkout() {
    if (!cart) return
    const selected = cart.items.filter((item) => item.selected)
    if (selected.length === 0) return
    setCheckingOut(true)
    try {
      const result = await api<CheckoutVo>(
        'POST',
        '/api/cart/checkout',
        { items: selected.map((item) => ({ itemId: item.id, quantity: item.quantity })) },
        { 'Idempotency-Key': newCheckoutKey() },
      )
      clearCheckoutKey()
      loadProfile()
      notify(t('cart.checkoutOk', { count: result.bookings?.length ?? 0 }), 'success')
      navigate('/bookings')
    } catch (e) {
      const { message, action } = resolveApiError(e, 'cart.checkoutFailed')
      notify({ message, action, tone: 'error' })
      load()
      loadProfile()
    } finally {
      setCheckingOut(false)
    }
  }

  async function checkoutAfterPriceConfirm() {
    setPriceConfirm(false)
    try {
      setCart(await api<CartVo>('POST', '/api/cart/refresh-prices'))
      await checkout()
    } catch (e) {
      const { message, action } = resolveApiError(e, 'common.operationFailed')
      notify({ message, action, tone: 'error' })
      load()
    }
  }

  if (loading) {
    return (
      <div className="page">
        <CartHeader />
        <SkeletonCard />
      </div>
    )
  }

  if (loadError) {
    return (
      <div className="page">
        <CartHeader />
        <EmptyState
          title={t('cart.loadFailed')}
          hint={loadError}
          action={
            <button
              className="btn-primary"
              onClick={() => {
                setLoading(true)
                load()
              }}
            >
              {t('common.retry')}
            </button>
          }
        />
      </div>
    )
  }

  const items = cart?.items ?? []
  const selectedItems = items.filter((item) => item.selected)
  const priceChanged = selectedItems.some((item) => item.issues.includes('PRICE_CHANGED'))
  const selectedTotalCents = cart?.selectedTotalCents ?? 0
  // 预检差额。结算按钮不因此禁用 —— 服务端才是权威，这里只是提前把问题说清楚。
  const shortfallCents = profile ? Math.max(0, selectedTotalCents - profile.walletCents) : 0

  return (
    <div className="page">
      <CartHeader />

      {items.length === 0 ? (
        <EmptyState
          title={t('cart.emptyTitle')}
          hint={t('cart.emptyHint')}
          action={
            <NavLink to="/" className="btn-primary btn-link">
              {t('cart.goEvents')}
            </NavLink>
          }
        />
      ) : (
        <>
          <ul className="stack-list">
            {items.map((item) => (
              <li key={item.id} className="card cart-row">
                <label className="cart-select">
                  <input
                    type="checkbox"
                    checked={item.selected}
                    disabled={busyItem === item.id}
                    onChange={(e) =>
                      mutate(() => api<CartVo>('PATCH', `/api/cart/items/${item.id}`, { selected: e.target.checked }), item.id)
                    }
                    aria-label={t('cart.selectAria', { title: item.eventTitle ?? item.id })}
                  />
                </label>
                <div className="cart-copy">
                  <h3>
                    <NavLink to={`/events/${item.eventId}`}>{item.eventTitle ?? `#${item.eventId}`}</NavLink>
                  </h3>
                  <p className="muted small">
                    {item.startsAt ? formatTime(item.startsAt) : null}
                    {item.eventStatus ? <EventStatusBadge status={item.eventStatus} /> : null}
                  </p>
                  <p className="muted small">
                    {t('cart.unitPrice', {
                      price: formatMoney(item.unitPriceCents),
                      current: item.currentUnitPriceCents !== item.unitPriceCents ? formatMoney(item.currentUnitPriceCents) : undefined,
                    })}
                  </p>
                  {item.issues.length > 0 && (
                    <ul className="cart-issues">
                      {item.issues.map((issue) => (
                        <li key={issue} className="error-text small">
                          {t(`cart.issue.${issue}`)}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
                <div className="cart-side">
                  {/* 数量上限与详情页「最多 N 张」同口径：min(限购, 余票)。
                      余票降为 0 时 min 为 0，「加一张」恒禁用，减一张不受影响。 */}
                  <div className="stepper stepper-sm">
                    <button
                      type="button"
                      className="btn-secondary btn-icon"
                      aria-label={t('detail.minus')}
                      disabled={busyItem === item.id || item.quantity <= 1}
                      onClick={() => mutate(() => api<CartVo>('PATCH', `/api/cart/items/${item.id}`, { quantity: item.quantity - 1 }), item.id)}
                    >
                      −
                    </button>
                    <span className="cart-qty" aria-live="polite">{item.quantity}</span>
                    <button
                      type="button"
                      className="btn-secondary btn-icon"
                      aria-label={t('detail.plus')}
                      disabled={busyItem === item.id || item.quantity >= Math.min(item.maxQuantityPerBooking, item.remaining)}
                      onClick={() => mutate(() => api<CartVo>('PATCH', `/api/cart/items/${item.id}`, { quantity: item.quantity + 1 }), item.id)}
                    >
                      +
                    </button>
                  </div>
                  <strong>{formatMoney(item.lineTotalCents)}</strong>
                  <button
                    className="btn-ghost btn-sm"
                    disabled={busyItem === item.id}
                    onClick={() => mutate(() => api<CartVo>('DELETE', `/api/cart/items/${item.id}`), item.id)}
                  >
                    {t('cart.remove')}
                  </button>
                </div>
              </li>
            ))}
          </ul>

          <ErrorNote message={loadError} />

          {shortfallCents > 0 && (
            <Alert
              tone="warn"
              title={t('cart.shortTitle')}
              action={{ label: t('errors.action.topUp'), to: '/profile' }}
            >
              {t('cart.shortBody', { amount: formatMoney(shortfallCents) })}
            </Alert>
          )}

          <div className="card cart-footer">
            <dl className="cart-summary">
              <div>
                <dt>{t('cart.selectedTotal', { count: selectedItems.length })}</dt>
                <dd className="balance-num num">{formatMoney(selectedTotalCents)}</dd>
              </div>
              {profile && (
                <div>
                  <dt>{t('cart.walletBalance')}</dt>
                  <dd className="num">{formatMoney(profile.walletCents)}</dd>
                </div>
              )}
              {shortfallCents > 0 && (
                <div className="cart-summary-short">
                  <dt>{t('cart.shortfall')}</dt>
                  <dd className="num">{formatMoney(shortfallCents)}</dd>
                </div>
              )}
            </dl>
            <p className="muted small">{t('cart.checkoutHint')}</p>
            <div className="row cart-actions">
              <button className="btn-ghost" onClick={() => setConfirmClear(true)} disabled={checkingOut}>
                {t('cart.clear')}
              </button>
              <button
                className="btn-primary"
                disabled={checkingOut || selectedItems.length === 0}
                onClick={() => (priceChanged ? setPriceConfirm(true) : checkout())}
              >
                {checkingOut ? t('cart.checkingOut') : t('cart.checkout')}
              </button>
            </div>
          </div>
        </>
      )}

      <ConfirmDialog
        open={confirmClear}
        title={t('cart.clearTitle')}
        description={t('cart.clearDesc')}
        tone="danger"
        confirmLabel={t('cart.clear')}
        onCancel={() => setConfirmClear(false)}
        onConfirm={() => {
          setConfirmClear(false)
          mutate(() => api<CartVo>('DELETE', '/api/cart'))
        }}
      />

      <ConfirmDialog
        open={priceConfirm}
        title={t('cart.priceChangedTitle')}
        description={t('cart.priceChangedDesc')}
        confirmLabel={t('cart.priceChangedConfirm')}
        busy={checkingOut}
        onCancel={() => setPriceConfirm(false)}
        onConfirm={() => {
          void checkoutAfterPriceConfirm()
        }}
      />
    </div>
  )
}

function CartHeader() {
  const { t } = useTranslation()
  return (
    <header className="page-head">
      <div>
        <h1>{t('cart.title')}</h1>
        <p className="muted">{t('cart.sub')}</p>
      </div>
    </header>
  )
}
