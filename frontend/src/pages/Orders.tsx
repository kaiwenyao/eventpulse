import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, formatMoney, formatTime, BOOKING_STATUS_LABEL } from '../api'

interface BookingListItem {
  id: string
  tierName: string
  quantity: number
  status: string
  refundState: string
  totalMinor: number | null
  currency: string
  expiresAt: string | null
}

export default function Orders() {
  const orders = useQuery({
    queryKey: ['orders'],
    queryFn: () => api<BookingListItem[]>('GET', '/api/v1/bookings'),
  })

  return (
    <div className="card">
      <h2>我的订单</h2>
      {orders.isLoading && <p className="muted">加载中…</p>}
      {orders.data && orders.data.length === 0 && <p className="muted">还没有订单。</p>}
      {orders.data && orders.data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>订单</th>
              <th>票档</th>
              <th>金额</th>
              <th>履约状态</th>
              <th>退款状态</th>
              <th>创建</th>
            </tr>
          </thead>
          <tbody>
            {orders.data.map((b) => (
              <tr key={b.id}>
                <td>
                  <Link to={`/bookings/${b.id}`}>{b.id.slice(0, 8)}…</Link>
                </td>
                <td>{b.tierName} × {b.quantity}</td>
                <td>{formatMoney(b.totalMinor, b.currency)}</td>
                <td>
                  <span className="badge">{BOOKING_STATUS_LABEL[b.status] ?? b.status}</span>
                </td>
                <td className="muted">{b.refundState}</td>
                <td className="muted">{formatTime(b.expiresAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
