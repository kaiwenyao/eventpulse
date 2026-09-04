import { FormEvent, useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { api, formatMoney, formatTime } from '../api'
import { resolveApiError } from '../lib/apiError'
import { streamUserEvents, INITIAL_BACKOFF_MS } from '../lib/sse'
import { LedgerVo, PageVo, UserProfile } from '../types'
import { EmptyState } from '../ui/Badges'
import { FilterBar } from '../ui/FilterBar'
import { Pager } from '../ui/Pager'
import { SkeletonRows } from '../ui/Skeleton'

const PAGE_SIZE = 10

/** 流水业务类型的筛选选项（与后端 wallet_ledger.biz_type 对应）。 */
const LEDGER_TYPES = ['RECHARGE', 'BOOKING_PAYMENT', 'BOOKING_REFUND', 'EVENT_CANCEL_REFUND', 'OPENING_BALANCE'] as const

function toIsoOrNull(value: string): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

/**
 * 余额明细：只读展示 wallet_ledger 流水（数据库为准）。
 * 支持收支类型与时间筛选、服务端分页；点击关联订单进入订单详情。
 * 收到钱包变化提醒后重新拉取；提醒丢失时依赖建连 / 手动刷新补偿。
 *
 * 顶部的当前余额取自 `/api/auth/profile` —— 一个叫「余额明细」的页面此前
 * 竟然不显示余额，用户得跳去个人中心才看得到。
 */
export function WalletLedgerPage() {
  const { t } = useTranslation()
  const [records, setRecords] = useState<LedgerVo[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [type, setType] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [profile, setProfile] = useState<UserProfile | null>(null)

  const load = useCallback(
    (targetPage: number) => {
      setLoading(true)
      const params = new URLSearchParams()
      if (type) params.set('type', type)
      const fromIso = toIsoOrNull(from)
      const toIso = toIsoOrNull(to)
      if (fromIso) params.set('from', fromIso)
      if (toIso) params.set('to', toIso)
      params.set('page', String(targetPage))
      params.set('size', String(PAGE_SIZE))
      api<PageVo<LedgerVo>>('GET', `/api/wallet/ledger?${params.toString()}`)
        .then((data) => {
          setRecords(Array.isArray(data.records) ? data.records : [])
          setTotal(data.total ?? 0)
          setLoadError('')
        })
        .catch((e) => {
          setLoadError(resolveApiError(e, 'ledger.loadFailed').message)
        })
        .finally(() => setLoading(false))
    },
    [type, from, to],
  )

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

  // 钱包变化（充值 / 扣款 / 退款）→ 刷新流水；断线窗口的变化由 onOpen 补偿拉取。
  useEffect(() => {
    const controller = new AbortController()
    const refresh = () => {
      load(page)
      loadProfile()
    }
    void streamUserEvents(refresh, controller.signal, INITIAL_BACKOFF_MS, refresh)
    return () => controller.abort()
  }, [load, loadProfile, page])

  function applyFilter(event: FormEvent) {
    event.preventDefault()
    setPage(0)
    load(0)
  }

  function pickType(value: string) {
    setType(value)
    setPage(0)
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const showSkeleton = loading && records.length === 0

  // 服务端不下发区间聚合，所以这里只敢声称是「本页」小计 —— 标成全量会误导。
  const pageIn = records.filter((r) => r.amountCents > 0).reduce((sum, r) => sum + r.amountCents, 0)
  const pageOut = records.filter((r) => r.amountCents < 0).reduce((sum, r) => sum - r.amountCents, 0)

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>{t('ledger.title')}</h1>
          <p className="muted">{t('ledger.sub')}</p>
        </div>
        <NavLink to="/profile" className="btn-secondary btn-link">
          {t('ledger.topUp')}
        </NavLink>
      </header>

      {profile && (
        <div className="stat-grid stat-grid-compact">
          <div className="stat-card stat-accent">
            <p className="stat-label">{t('ledger.balanceNow')}</p>
            <p className="stat-value num">{formatMoney(profile.walletCents)}</p>
          </div>
          <div className="stat-card">
            <p className="stat-label">{t('ledger.spentTotal')}</p>
            <p className="stat-value num">{formatMoney(profile.totalSpentCents)}</p>
          </div>
          <div className="stat-card">
            <p className="stat-label">{t('ledger.entryCount')}</p>
            <p className="stat-value num">{total}</p>
          </div>
        </div>
      )}

      <FilterBar
        chipsLabel={t('ledger.type')}
        chips={[
          { value: '', label: t('common.all') },
          ...LEDGER_TYPES.map((value) => ({ value, label: t(`ledger.typeName.${value}`) })),
        ]}
        activeChip={type}
        onChipChange={pickType}
        onSubmit={applyFilter}
        advanced={
          <>
            <div className="field">
              <label htmlFor="ledger-from">{t('ledger.from')}</label>
              <input id="ledger-from" type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="ledger-to">{t('ledger.to')}</label>
              <input id="ledger-to" type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
            </div>
          </>
        }
      />

      {showSkeleton ? (
        <SkeletonRows count={6} />
      ) : loadError ? (
        <EmptyState
          title={t('ledger.loadFailed')}
          hint={loadError}
          action={
            <button className="btn-primary" onClick={() => load(page)}>
              {t('common.retry')}
            </button>
          }
        />
      ) : records.length === 0 ? (
        <EmptyState title={t('ledger.emptyTitle')} hint={t('ledger.emptyHint')} />
      ) : (
        <>
          <p className="ledger-subtotal muted small">
            <span>
              {t('ledger.pageIn')} <strong className="num">+{formatMoney(pageIn)}</strong>
            </span>
            <span>
              {t('ledger.pageOut')} <strong className="num">−{formatMoney(pageOut)}</strong>
            </span>
          </p>

          <ul className="stack-list" aria-busy={loading}>
            {records.map((entry) => (
              <LedgerRow key={entry.id} entry={entry} />
            ))}
          </ul>

          <Pager
            page={page}
            totalPages={totalPages}
            total={total}
            ariaLabel={t('ledger.pagerAria')}
            onChange={setPage}
          />
        </>
      )}
    </div>
  )
}

function LedgerRow({ entry }: { entry: LedgerVo }) {
  const { t } = useTranslation()
  const income = entry.amountCents > 0

  return (
    <li className="ledger-row">
      <div className="ledger-line">
        <strong className="ledger-type">
          {t(`ledger.typeName.${entry.bizType}`, { defaultValue: entry.bizType })}
        </strong>
        <span className="ledger-when muted small">{formatTime(entry.createdAt)}</span>
        {/* 单一强调色系统里没有绿色可用，收支靠符号与字重区分，不靠色相。 */}
        <strong className={`ledger-amount num ${income ? 'ledger-in' : 'ledger-out'}`}>
          {income ? '+' : '−'}
          {formatMoney(Math.abs(entry.amountCents))}
        </strong>
        <span className="ledger-balance num small">
          {t('ledger.balanceAfter', { amount: formatMoney(entry.balanceAfterCents) })}
        </span>
      </div>

      <p className="ledger-meta muted small">
        <span>{t('ledger.seq', { seq: entry.seqNo })}</span>
        <span className="num">
          {t('ledger.balanceFlow', {
            before: formatMoney(entry.balanceBeforeCents),
            after: formatMoney(entry.balanceAfterCents),
          })}
        </span>
        {entry.checkoutId && <span>{t('ledger.checkoutRef', { id: entry.checkoutId })}</span>}
        {entry.description && <span>{entry.description}</span>}
        {entry.bookingId && (
          <NavLink to={`/bookings/${entry.bookingId}`}>{t('ledger.orderLink', { id: entry.bookingId })}</NavLink>
        )}
      </p>
    </li>
  )
}
