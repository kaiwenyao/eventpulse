import { FormEvent, useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { api, ApiError, formatMoney, formatTime } from '../api'
import { streamUserEvents, INITIAL_BACKOFF_MS } from '../lib/sse'
import { LedgerVo, PageVo } from '../types'
import { EmptyState, ErrorNote } from '../ui/Badges'
import { SkeletonCard } from '../ui/Skeleton'

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
  const pageSize = 10

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
      params.set('size', String(pageSize))
      api<PageVo<LedgerVo>>('GET', `/api/wallet/ledger?${params.toString()}`)
        .then((data) => {
          setRecords(Array.isArray(data.records) ? data.records : [])
          setTotal(data.total ?? 0)
          setLoadError('')
        })
        .catch((e) => {
          setLoadError(e instanceof ApiError ? e.message : t('ledger.loadFailed'))
        })
        .finally(() => setLoading(false))
    },
    [type, from, to, t],
  )

  useEffect(() => {
    load(page)
  }, [load, page])

  // 钱包变化（充值 / 扣款 / 退款）→ 刷新流水；断线窗口的变化由 onOpen 补偿拉取。
  useEffect(() => {
    const controller = new AbortController()
    void streamUserEvents(() => load(page), controller.signal, INITIAL_BACKOFF_MS, () => load(page))
    return () => controller.abort()
  }, [load, page])

  function applyFilter(event: FormEvent) {
    event.preventDefault()
    setPage(0)
    load(0)
  }

  const totalPages = Math.max(1, Math.ceil(total / pageSize))

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>{t('ledger.title')}</h1>
          <p className="muted">{t('ledger.sub')}</p>
        </div>
      </header>

      <form className="card ledger-filters" onSubmit={applyFilter}>
        <div className="field">
          <label htmlFor="ledger-type">{t('ledger.type')}</label>
          <select id="ledger-type" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">{t('common.all')}</option>
            {LEDGER_TYPES.map((value) => (
              <option key={value} value={value}>
                {t(`ledger.typeName.${value}`)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="ledger-from">{t('ledger.from')}</label>
          <input
            id="ledger-from"
            type="datetime-local"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="ledger-to">{t('ledger.to')}</label>
          <input
            id="ledger-to"
            type="datetime-local"
            value={to}
            onChange={(e) => setTo(e.target.value)}
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
          <ul className="stack-list">
            {records.map((entry) => {
              const income = entry.amountCents > 0
              return (
                <li key={entry.id} className="card ledger-row">
                  <div className="ledger-copy">
                    <strong>{t(`ledger.typeName.${entry.bizType}`, { defaultValue: entry.bizType })}</strong>
                    <p className="muted small">
                      {formatTime(entry.createdAt)}
                      {' · '}
                      {t('ledger.seq', { seq: entry.seqNo })}
                    </p>
                    {entry.description && <p className="muted small">{entry.description}</p>}
                  </div>
                  <div className="ledger-side">
                    <strong className={income ? 'ledger-in' : 'ledger-out'}>
                      {income ? '+' : '-'}
                      {formatMoney(Math.abs(entry.amountCents))}
                    </strong>
                    <p className="muted small">{t('ledger.balanceAfter', { amount: formatMoney(entry.balanceAfterCents) })}</p>
                    {entry.bookingId && (
                      <NavLink className="btn-ghost btn-sm" to={`/bookings/${entry.bookingId}`}>
                        {t('ledger.orderLink', { id: entry.bookingId })}
                      </NavLink>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>

          <ErrorNote message={loadError} />

          <nav className="pager" aria-label={t('ledger.pagerAria')}>
            <button className="btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
              {t('ledger.prev')}
            </button>
            <span className="muted small">
              {t('ledger.pageOf', { page: page + 1, total: totalPages })}
            </span>
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
