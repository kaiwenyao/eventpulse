import { useTranslation } from 'react-i18next'

/** Layout-preserving placeholders shown while a page's first fetch is in flight. */
export function SkeletonLine({ width = '100%' }: { width?: string }) {
  return <span className="skeleton skeleton-line" style={{ width }} aria-hidden />
}

export function SkeletonCard() {
  return (
    <div className="skeleton-card" aria-hidden>
      <SkeletonLine width="34%" />
      <SkeletonLine width="82%" />
      <SkeletonLine width="55%" />
    </div>
  )
}

export function SkeletonGrid({ count = 6, label }: { count?: number; label?: string }) {
  const { t } = useTranslation()
  return (
    <div className="grid" role="status" aria-label={label ?? t('common.loading')}>
      {Array.from({ length: count }, (_, i) => (
        <SkeletonCard key={i} />
      ))}
    </div>
  )
}

/**
 * 行形骨架：与密集对齐行同高，加载时不会像整块卡片骨架那样把列表撑变形。
 */
export function SkeletonRows({ count = 5, label }: { count?: number; label?: string }) {
  const { t } = useTranslation()
  return (
    <ul className="stack-list" role="status" aria-label={label ?? t('common.loading')}>
      {Array.from({ length: count }, (_, i) => (
        <li key={i} className="skeleton-row" aria-hidden>
          <SkeletonLine width="42%" />
          <SkeletonLine width="68%" />
        </li>
      ))}
    </ul>
  )
}
