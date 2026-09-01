import { useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { api } from '../api'
import { EventTicket } from '../components/EventTicket'
import { EventVo, PageVo } from '../types'
import { EmptyState } from '../ui/Badges'
import { SkeletonGrid } from '../ui/Skeleton'

export function FavouritesPage() {
  const [items, setItems] = useState<EventVo[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api<PageVo<EventVo>>('GET', '/api/favourites')
      .then((page) => setItems(page?.records ?? []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false))
  }, [])

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>我的收藏</h1>
          <p className="muted">收藏的活动开票或变更时，你会第一时间收到消息。</p>
        </div>
      </header>
      {loading ? (
        <SkeletonGrid count={3} label="正在加载收藏" />
      ) : items.length === 0 ? (
        <EmptyState
          title="还没有收藏"
          hint="在活动详情页点击收藏，方便下次找回来。"
          action={
            <NavLink to="/" className="btn-primary btn-link">
              去发现活动
            </NavLink>
          }
        />
      ) : (
        <div className="grid">
          {items.map((event) => (
            <EventTicket key={event.id} event={event} />
          ))}
        </div>
      )}
    </div>
  )
}
