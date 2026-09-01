import { useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { api, ApiError, formatTime } from '../api'
import { BookingVo } from '../types'
import { BookingStatusBadge, EmptyState } from '../ui/Badges'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'

export function BookingsPage() {
  const [items, setItems] = useState<BookingVo[]>([])
  const [loading, setLoading] = useState(true)
  const { notify } = useToast()

  useEffect(() => {
    api<BookingVo[]>('GET', '/api/bookings')
      .then((data) => setItems(Array.isArray(data) ? data : []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false))
  }, [])

  async function cancel(booking: BookingVo) {
    try {
      const updated = await api<BookingVo>('POST', `/api/bookings/${booking.id}/cancel`)
      setItems((prev) => prev.map((x) => (x.id === booking.id ? updated : x)))
      notify('订单已取消', 'success')
    } catch (e) {
      notify(e instanceof ApiError ? e.message : '取消失败', 'error')
    }
  }

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>我的预订</h1>
          <p className="muted">所有订单与电子票都在这里，取消后库存会立即释放。</p>
        </div>
      </header>

      {loading ? (
        <SkeletonCard />
      ) : items.length === 0 ? (
        <EmptyState
          title="还没有预订"
          hint="去活动页挑一张喜欢的票吧。"
          action={
            <NavLink to="/" className="btn-primary btn-link">
              去看看活动
            </NavLink>
          }
        />
      ) : (
        <ul className="stack-list">
          {items.map((b) => (
            <li key={b.id} className="card booking-row">
              <div className="booking-copy">
                <h3>
                  <NavLink to={`/bookings/${b.id}`}>{b.eventTitle}</NavLink>
                </h3>
                <p className="muted small">
                  {b.quantity} 张 · 下单于 {formatTime(b.createdAt)}
                </p>
              </div>
              <div className="row booking-actions">
                <BookingStatusBadge status={b.status} />
                {b.status === 'CONFIRMED' && (
                  <button className="btn-secondary btn-sm" onClick={() => cancel(b)}>
                    取消
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
