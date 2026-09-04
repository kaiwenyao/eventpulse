import { useTranslation } from 'react-i18next'

interface PagerProps {
  /** 0-indexed，与后端分页参数一致。 */
  page: number
  totalPages: number
  total: number
  ariaLabel: string
  onChange: (page: number) => void
}

/**
 * 服务端分页的上一页 / 页码 / 下一页。预订与余额明细此前逐字重复了同一段标记，
 * 抽出来后两页的分页行为与文案自动保持一致。
 */
export function Pager({ page, totalPages, total, ariaLabel, onChange }: PagerProps) {
  const { t } = useTranslation()
  return (
    <nav className="pager" aria-label={ariaLabel}>
      <button className="btn-secondary btn-sm" disabled={page === 0} onClick={() => onChange(Math.max(0, page - 1))}>
        {t('ledger.prev')}
      </button>
      <span className="pager-status muted small">
        <span>{t('ledger.pageOf', { page: page + 1, total: totalPages })}</span>
        <span>{t('common.totalRows', { count: total })}</span>
      </span>
      <button
        className="btn-secondary btn-sm"
        disabled={page + 1 >= totalPages}
        onClick={() => onChange(page + 1)}
      >
        {t('ledger.next')}
      </button>
    </nav>
  )
}
