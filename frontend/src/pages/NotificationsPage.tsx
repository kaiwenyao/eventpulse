import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError, formatTime } from '../api'
import { NotificationVo } from '../types'
import { EmptyState } from '../ui/Badges'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'

export function NotificationsPage() {
  const { t } = useTranslation()
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
      notify(e instanceof ApiError ? e.message : t('common.operationFailed'), 'error')
    }
  }

  async function markAllRead() {
    const ids = items.map((n) => n.id)
    await Promise.allSettled(ids.map((id) => api('POST', `/api/notifications/${id}/read`)))
    setItems([])
    notify(t('notifications.allRead'), 'success')
  }

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>{t('notifications.pageTitle')}</h1>
          <p className="muted">{t('notifications.sub')}</p>
        </div>
        {items.length > 0 && (
          <button className="btn-secondary btn-sm" onClick={markAllRead}>
            {t('notifications.markAll')}
          </button>
        )}
      </header>

      {loading ? (
        <SkeletonCard />
      ) : items.length === 0 ? (
        <EmptyState title={t('notifications.emptyTitle')} hint={t('notifications.emptyHint')} />
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
                {t('notifications.markRead')}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
