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

export function SkeletonGrid({ count = 6, label = '加载中…' }: { count?: number; label?: string }) {
  return (
    <div className="grid" role="status" aria-label={label}>
      {Array.from({ length: count }, (_, i) => (
        <SkeletonCard key={i} />
      ))}
    </div>
  )
}
