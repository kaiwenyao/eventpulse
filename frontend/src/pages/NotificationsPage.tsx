import { useEffect, useState } from 'react'
import { api, ApiError, formatTime } from '../api'
import { NotificationVo } from '../types'
import { EmptyState } from '../ui/Badges'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'

export function NotificationsPage() {
  const [items, setItems] = useState<NotificationVo[]>([])
  const [loading, setLoading] = useState(true)
  const { notify } = useToast()

  useEffect(() => {
    api<NotificationVo[]>('GET', '/api/notifications')
      .then((data) => setItems(Array.isArray(data) ? data : []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false))
  }, [])

  async function markRead(id: number) {
    try {
      await api('POST', `/api/notifications/${id}/read`)
      setItems((prev) => prev.filter((x) => x.id !== id))
    } catch (e) {
      notify(e instanceof ApiError ? e.message : '操作失败', 'error')
    }
  }

  async function markAllRead() {
    const ids = items.map((n) => n.id)
    await Promise.allSettled(ids.map((id) => api('POST', `/api/notifications/${id}/read`)))
    setItems([])
    notify('已全部标为已读', 'success')
  }

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>消息中心</h1>
          <p className="muted">预订、变更、取消和提醒都会出现在这里。</p>
        </div>
        {items.length > 0 && (
          <button className="btn-secondary btn-sm" onClick={markAllRead}>
            全部已读
          </button>
        )}
      </header>

      {loading ? (
        <SkeletonCard />
      ) : items.length === 0 ? (
        <EmptyState title="还没有消息" hint="预订一场活动后，通知会送到这里。" />
      ) : (
        <ul className="stack-list">
          {items.map((n) => (
            <li key={n.id} className="card notification-row">
              <span className="notification-dot" aria-hidden />
              <div className="notification-copy">
                <p>{n.title ? `${n.title} · ${n.message}` : n.message}</p>
                <p className="muted small">{formatTime(n.createdAt)}</p>
              </div>
              <button className="btn-secondary btn-sm" onClick={() => markRead(n.id)}>
                标为已读
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
