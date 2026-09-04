import { FormEvent, useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { api, formatMoney, formatTime } from '../api'
import { resolveApiError } from '../lib/apiError'
import { streamUserEvents, INITIAL_BACKOFF_MS } from '../lib/sse'
import { BookingVo, PageVo, UserProfile } from '../types'
import { BookingStatusBadge, EmptyState } from '../ui/Badges'
import { FilterBar } from '../ui/FilterBar'
import { ConfirmDialog } from '../ui/Modal'
import { Pager } from '../ui/Pager'
import { SkeletonRows } from '../ui/Skeleton'
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
 *
 * 布局是「密集对齐行」：第一行列对齐（活动 / 时间 / 数量 / 实付 / 状态 / 操作），
 * 第二行放次级线索（下单时间、订单号、核销进度、退款、同次结算）。金额与标题
 * 承担字重，时间戳退到次级 —— 改版前所有字段挤在同一句灰字里，无从抓重点。
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
  const [confirmTarget, setConfirmTarget] = useState<BookingVo | null>(null)
  const [profile, setProfile] = useState<UserProfile | null>(null)

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
          setLoadError(resolveApiError(e, 'bookings.loadFailed').message)
        })
        .finally(() => setLoading(false))
    },
    [status, from, to, query],
  )

  // KPI 条的口径是「全部订单」，所以取账户维度的统计，而不是当前页聚合 ——
  // 按页聚合会随翻页跳动，读起来像是全量数字，属于误导。
  const loadProfile = useCallback(() => {
    api<UserProfile>('GET', '/api/auth/profile')
      .then(setProfile)
      .catch(() => setProfile(null))
  }, [])

  useEffect(() => {
    load(page)
  }, [load, page])

  useEffect(() => {
    loadProfile()
  }, [loadProfile])

  // 下单 / 取消 / 购物车结算（本页或其他页面、其他设备）→ 刷新订单列表。
  useEffect(() => {
    const controller = new AbortController()
    const refresh = () => {
      load(page)
      loadProfile()
    }
    void streamUserEvents(refresh, controller.signal, INITIAL_BACKOFF_MS, refresh)
    return () => controller.abort()
  }, [load, loadProfile, page])

  async function cancel(booking: BookingVo) {
    setConfirmTarget(null)
    setCancelling(booking.id)
    try {
      await api<BookingVo>('POST', `/api/bookings/${booking.id}/cancel`)
      notify(t('bookings.cancelled'), 'success')
      load(page)
      loadProfile()
    } catch (e) {
      const { message, action } = resolveApiError(e, 'bookings.cancelFailed')
      notify({ message, action, tone: 'error' })
    } finally {
      setCancelling(null)
    }
  }

  function applyFilters(event: FormEvent) {
    event.preventDefault()
    setPage(0)
    load(0)
  }

  function pickStatus(value: string) {
    setStatus(value)
    setPage(0)
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  // 首次加载才清空列表；SSE 后台重拉时保留旧行，避免列表每次刷新都整块消失。
  const showSkeleton = loading && items.length === 0

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>{t('bookings.title')}</h1>
          <p className="muted">{t('bookings.sub')}</p>
        </div>
      </header>

      {profile && (
        <div className="stat-grid stat-grid-compact">
          <div className="stat-card stat-accent">
            <p className="stat-label">{t('bookings.kpiOrders')}</p>
            <p className="stat-value num">{profile.bookingCount}</p>
          </div>
          <div className="stat-card">
            <p className="stat-label">{t('bookings.kpiSpent')}</p>
            <p className="stat-value num">{formatMoney(profile.totalSpentCents)}</p>
          </div>
          <div className="stat-card">
            <p className="stat-label">{t('bookings.kpiTickets')}</p>
            <p className="stat-value num">{profile.ticketCount}</p>
          </div>
        </div>
      )}

      <FilterBar
        chipsLabel={t('bookings.statusFilter')}
        chips={STATUS_FILTERS.map((value) => ({
          value,
          label: value === '' ? t('common.all') : t(`status.booking.${value}`),
        }))}
        activeChip={status}
        onChipChange={pickStatus}
        onSubmit={applyFilters}
        inline={
          <div className="filter-search">
            <label className="sr-only" htmlFor="bookings-q">
              {t('bookings.searchLabel')}
            </label>
            <input
              id="bookings-q"
              type="search"
              value={query}
              placeholder={t('bookings.searchPlaceholder')}
              onChange={(e) => setQuery(e.target.value)}
            />
            <button type="submit" className="btn-secondary btn-sm">
              {t('common.search')}
            </button>
          </div>
        }
        advanced={
          <>
            <div className="field">
              <label htmlFor="bookings-from">{t('bookings.from')}</label>
              <input id="bookings-from" type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="bookings-to">{t('bookings.to')}</label>
              <input id="bookings-to" type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
            </div>
          </>
        }
      />

      {showSkeleton ? (
        <SkeletonRows count={5} />
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
          <ul className="stack-list" aria-busy={loading}>
            {items.map((b) => (
              <BookingRow
                key={b.id}
                booking={b}
                busy={cancelling === b.id}
                onCancel={() => setConfirmTarget(b)}
              />
            ))}
          </ul>

          <Pager
            page={page}
            totalPages={totalPages}
            total={total}
            ariaLabel={t('bookings.pagerAria')}
            onChange={setPage}
          />
        </>
      )}

      <ConfirmDialog
        open={confirmTarget !== null}
        title={t('bookings.cancelConfirmTitle')}
        description={t('bookings.cancelConfirmDesc')}
        tone="danger"
        confirmLabel={t('bookings.cancelOrder')}
        busy={cancelling !== null}
        onCancel={() => setConfirmTarget(null)}
        onConfirm={() => confirmTarget && cancel(confirmTarget)}
      />
    </div>
  )
}

interface BookingRowProps {
  booking: BookingVo
  busy: boolean
  onCancel: () => void
}

function BookingRow({ booking: b, busy, onCancel }: BookingRowProps) {
  const { t } = useTranslation()
  const valid = b.validCount ?? 0
  const checkedIn = b.checkedInCount ?? 0
  const ticketTotal = valid + checkedIn
  const refunded = typeof b.refundCents === 'number' && b.refundCents > 0

  // 服务端下发的 `cancellable` 才是权威口径，与订单详情页保持一致；
  // 改版前这里用的是 `status === 'CONFIRMED'`，两处会给出不同答案。
  const canCancel = b.cancellable === true

  return (
    <li className="booking-row">
      <div className="booking-line">
        <h3 className="booking-title">
          <NavLink to={`/bookings/${b.id}`}>{b.eventTitle}</NavLink>
        </h3>
        <span className="booking-when muted small">{b.eventStartsAt ? formatTime(b.eventStartsAt) : '—'}</span>
        <span className="booking-qty num small">{t('bookings.qtyValue', { count: b.quantity })}</span>
        <strong
          className="booking-paid num"
          title={b.unitPriceCents ? t('bookings.unitPrice') + ' ' + formatMoney(b.unitPriceCents) : undefined}
        >
          {formatMoney(b.paidCents)}
        </strong>
        <span className="booking-status">
          <BookingStatusBadge status={b.status} />
        </span>
        <span className="booking-actions">
          {canCancel && (
            <button className="btn-secondary btn-sm" disabled={busy} onClick={onCancel}>
              {busy ? t('common.processing') : t('bookings.cancel')}
            </button>
          )}
        </span>
      </div>

      <p className="booking-meta muted small">
        <span>{t('bookings.placedAt2', { time: formatTime(b.createdAt) })}</span>
        <span>{t('bookings.orderNum', { id: b.id })}</span>
        {ticketTotal > 0 && (
          <span>
            {checkedIn > 0
              ? t('bookings.checkinProgress', { done: checkedIn, total: ticketTotal })
              : t('bookings.pendingCheckin', { count: valid })}
          </span>
        )}
        {refunded && <span>{t('bookings.refundLine', { amount: formatMoney(b.refundCents!) })}</span>}
        {b.checkoutId && <span>{t('bookings.checkoutRef', { id: b.checkoutId })}</span>}
        {!canCancel && b.cancelBlockReason && <span>{t(`bookings.blockReason.${b.cancelBlockReason}`)}</span>}
      </p>
    </li>
  )
}
