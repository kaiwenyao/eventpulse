import { FormEvent, useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { api, ApiError, formatMoney, formatTime } from '../api'
import { streamUserEvents, INITIAL_BACKOFF_MS } from '../lib/sse'
import { BookingVo, PageVo } from '../types'
import { BookingStatusBadge, EmptyState } from '../ui/Badges'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'

const PAGE_SIZE = 10

const STATUS_FILTERS = ['', 'CONFIRMED', 'CANCELLED'] as const

function toIsoOrNull(value: string): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

/**
 * 我的历史订单：默认展示全部真实状态（含已取消），服务端分页 / 筛选 / 搜索。
 * 加载失败与「暂无订单」明确区分，并提供重试；订单变化提醒（SSE）到达后
 * 重新查询数据库 —— 提醒只是信号，数据永远以 REST 查询为准。
 */
export function BookingsPage() {
  const { t } = useTranslation()
  const { notify } = useToast()
  const [items, setItems] = useState<BookingVo[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState<string>('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [cancelling, setCancelling] = useState<number | null>(null)

  const load = useCallback(
    (targetPage: number) => {
      setLoading(true)
      const params = new URLSearchParams()
      if (status) params.set('status', status)
      const fromIso = toIsoOrNull(from)
      const toIso = toIsoOrNull(to)
      if (fromIso) params.set('from', fromIso)
      if (toIso) params.set('to', toIso)
      if (query.trim()) params.set('q', query.trim())
      params.set('page', String(targetPage))
      params.set('size', String(PAGE_SIZE))
      api<PageVo<BookingVo>>('GET', `/api/bookings?${params.toString()}`)
        .then((data) => {
          setItems(Array.isArray(data.records) ? data.records : [])
          setTotal(data.total ?? 0)
          setLoadError('')
        })
        .catch((e) => {
          // 加载失败 ≠ 没有订单：保留错误态，不覆盖成空态。
          setLoadError(e instanceof ApiError ? e.message : t('bookings.loadFailed'))
        })
        .finally(() => setLoading(false))
    },
    [status, from, to, query, t],
  )

  useEffect(() => {
    load(page)
  }, [load, page])

  // 下单 / 取消 / 购物车结算（本页或其他页面、其他设备）→ 刷新订单列表。
  useEffect(() => {
    const controller = new AbortController()
    void streamUserEvents(() => load(page), controller.signal, INITIAL_BACKOFF_MS, () => load(page))
    return () => controller.abort()
  }, [load, page])

  async function cancel(booking: BookingVo) {
    setCancelling(booking.id)
    try {
      await api<BookingVo>('POST', `/api/bookings/${booking.id}/cancel`)
      notify(t('bookings.cancelled'), 'success')
      load(page)
    } catch (e) {
      notify(e instanceof ApiError ? e.message : t('bookings.cancelFailed'), 'error')
    } finally {
      setCancelling(null)
    }
  }

  function applyFilters(event: FormEvent) {
    event.preventDefault()
    setPage(0)
    load(0)
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>{t('bookings.title')}</h1>
          <p className="muted">{t('bookings.sub')}</p>
        </div>
      </header>

      <form className="card ledger-filters" onSubmit={applyFilters}>
        <div className="field">
          <label htmlFor="bookings-status">{t('bookings.statusFilter')}</label>
          <select id="bookings-status" value={status} onChange={(e) => setStatus(e.target.value)}>
            {STATUS_FILTERS.map((value) => (
              <option key={value} value={value}>
                {value === '' ? t('common.all') : t(`status.booking.${value}`)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="bookings-from">{t('bookings.from')}</label>
          <input id="bookings-from" type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="bookings-to">{t('bookings.to')}</label>
          <input id="bookings-to" type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="bookings-q">{t('bookings.searchLabel')}</label>
          <input
            id="bookings-q"
            type="search"
            value={query}
            placeholder={t('bookings.searchPlaceholder')}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <div className="field ledger-filter-action">
          <button type="submit" className="btn-secondary">
            {t('ledger.apply')}
          </button>
        </div>
      </form>

      {loading ? (
        <SkeletonCard />
      ) : loadError ? (
        <EmptyState
          title={t('bookings.loadFailed')}
          hint={loadError}
          action={
            <button className="btn-primary" onClick={() => load(page)}>
              {t('common.retry')}
            </button>
          }
        />
      ) : items.length === 0 ? (
        <EmptyState
          title={t('bookings.emptyTitle')}
          hint={t('bookings.emptyHint')}
          action={
            <NavLink to="/" className="btn-primary btn-link">
              {t('bookings.goEvents')}
            </NavLink>
          }
        />
      ) : (
        <>
          <ul className="stack-list">
            {items.map((b) => (
              <li key={b.id} className="card booking-row">
                <div className="booking-copy">
                  <h3>
                    <NavLink to={`/bookings/${b.id}`}>{b.eventTitle}</NavLink>
                  </h3>
                  <p className="muted small">
                    {t('bookings.qtyLine', { count: b.quantity, time: formatTime(b.createdAt) })}
                    {' · '}
                    {t('bookings.paidLine', { amount: formatMoney(b.paidCents) })}
                    {typeof b.refundCents === 'number' && b.refundCents > 0
                      ? ` · ${t('bookings.refundLine', { amount: formatMoney(b.refundCents) })}`
                      : null}
                  </p>
                  {b.status !== 'CONFIRMED' && b.cancelBlockReason && (
                    <p className="muted small">{t(`bookings.blockReason.${b.cancelBlockReason}`)}</p>
                  )}
                </div>
                <div className="row booking-actions">
                  <BookingStatusBadge status={b.status} />
                  {b.status === 'CONFIRMED' && (
                    <button
                      className="btn-secondary btn-sm"
                      disabled={cancelling === b.id}
                      onClick={() => cancel(b)}
                    >
                      {cancelling === b.id ? t('common.processing') : t('bookings.cancel')}
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>

          <nav className="pager" aria-label={t('bookings.pagerAria')}>
            <button className="btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              {t('ledger.prev')}
            </button>
            <span className="muted small">{t('ledger.pageOf', { page: page + 1, total: totalPages })}</span>
            <button
              className="btn-secondary btn-sm"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage((p) => p + 1)}
            >
              {t('ledger.next')}
            </button>
          </nav>
        </>
      )}
    </div>
  )
}
