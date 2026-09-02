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
