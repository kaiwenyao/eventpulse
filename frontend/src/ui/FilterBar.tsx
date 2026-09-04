import { FormEvent, ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

export interface FilterChip {
  /** 空串代表「全部」。 */
  value: string
  label: string
}

interface FilterBarProps {
  chips: readonly FilterChip[]
  activeChip: string
  onChipChange: (value: string) => void
  chipsLabel: string
  /** 折叠在「更多筛选」里的次级控件（时间范围等）。 */
  advanced?: ReactNode
  /** 与 chips 同排的主控件（搜索框等）。 */
  inline?: ReactNode
  onSubmit: (event: FormEvent) => void
}

/**
 * 列表页共用的筛选栏。主维度用 chips 直接摊开（一眼可见、一次点击即生效），
 * 次级维度收进 `<details>`，避免像改版前那样一整张四栏卡片比内容还抢眼。
 */
export function FilterBar({
  chips,
  activeChip,
  onChipChange,
  chipsLabel,
  advanced,
  inline,
  onSubmit,
}: FilterBarProps) {
  const { t } = useTranslation()
  return (
    <form className="filter-bar" onSubmit={onSubmit}>
      <div className="filter-main">
        <div className="chips" role="group" aria-label={chipsLabel}>
          {chips.map((chip) => (
            <button
              key={chip.value}
              type="button"
              className={`chip${chip.value === activeChip ? ' active' : ''}`}
              aria-pressed={chip.value === activeChip}
              onClick={() => onChipChange(chip.value)}
            >
              {chip.label}
            </button>
          ))}
        </div>
        {inline}
      </div>
      {advanced && (
        <details className="filter-advanced">
          <summary>{t('common.moreFilters')}</summary>
          <div className="filter-advanced-body">
            {advanced}
            <button type="submit" className="btn-secondary btn-sm">
              {t('ledger.apply')}
            </button>
          </div>
        </details>
      )}
    </form>
  )
}
