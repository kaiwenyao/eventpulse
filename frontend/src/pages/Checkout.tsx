import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, formatMoney, formatTime, newIdempotencyKey, ApiError } from '../api'

interface WalletView {
  availableAmountMinor: number
  currency: string
}

interface BookingView {
  id: string
  eventId: string
  tierName: string
  quantity: number
  status: string
  entitlementStatus: string
  refundState: string
  totalMinor: number | null
  currency: string
  priceSnapshot: Record<string, unknown>
  policySnapshot: Record<string, unknown>
  expiresAt: string | null
  activeIntent: { id: string; state: string; providerKey: string } | null
  tickets: Array<{ id: string; sequence: number; status: string }>
}

function Countdown({ expiresAt, onZero }: { expiresAt: string; onZero: () => void }) {
  const [remaining, setRemaining] = useState(() => new Date(expiresAt).getTime() - Date.now())
  const fired = useRef(false)
  useEffect(() => {
    const timer = setInterval(() => {
      const next = new Date(expiresAt).getTime() - Date.now()
      setRemaining(next)
      if (next <= 0 && !fired.current) {
        fired.current = true
        onZero()
      }
    }, 1000)
    return () => clearInterval(timer)
  }, [expiresAt, onZero])
  if (remaining <= 0) return <span className="countdown">已到期</span>
  const minutes = Math.floor(remaining / 60000)
  const seconds = Math.floor((remaining % 60000) / 1000)
  return <span className="countdown">{minutes}:{String(seconds).padStart(2, '0')}</span>
}

export default function Checkout() {
  const { bookingId } = useParams()
  const [error, setError] = useState('')
  const [paying, setPaying] = useState(false)
  // A fresh high-entropy key per attempt: a business failure (e.g. 409
  // BOOKING_NOT_PAYABLE) rolls the idempotency claim back server-side, and a
  // fresh key keeps the next click a clean request instead of replaying tomb-
  // stones. Idempotency of the payment itself is enforced server-side by the
  // single active payment intent per booking.
  const payKeyRef = useRef('')

  const booking = useQuery({
    queryKey: ['booking', bookingId],
    queryFn: () => api<BookingView>('GET', `/api/v1/bookings/${bookingId}`),
    refetchInterval: 2000,
  })
  const wallet = useQuery({
    queryKey: ['me'],
    queryFn: () => api<WalletView>('GET', '/api/v1/auth/me'),
  })

  async function pay() {
    setPaying(true)
    setError('')
    payKeyRef.current = newIdempotencyKey()
    try {
      await api('POST', `/api/v1/bookings/${bookingId}/pay`, {}, { idempotencyKey: payKeyRef.current })
      await Promise.all([booking.refetch(), wallet.refetch()])
    } catch (err) {
      if (err instanceof ApiError && err.code === 'INSUFFICIENT_BALANCE') {
        setError('钱包余额不足，无法完成支付。订单仍为待支付，到期将释放库存。')
      } else {
        setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '网络错误')
      }
      await wallet.refetch()
    } finally {
      setPaying(false)
    }
  }

  if (!booking.data) return <p className="muted">加载中…</p>
  const b = booking.data

  return (
    <>
      <div className="card">
        <div className="row spread">
          <h2 style={{ margin: 0 }}>结算 · {b.tierName} × {b.quantity}</h2>
          {b.status === 'PAYMENT_PENDING' && b.expiresAt && (
            <Countdown
              expiresAt={b.expiresAt}
              onZero={() => booking.refetch()}
            />
          )}
        </div>
        <p>
          应付金额 <span className="big-price">{formatMoney(b.totalMinor, b.currency)}</span>
        </p>
        <p>
          钱包余额 <span className="big-price">{formatMoney(wallet.data?.availableAmountMinor, wallet.data?.currency ?? b.currency)}</span>
        </p>
        <p className="muted">
          价格与政策来自购买时快照（政策 v{String(b.policySnapshot?.['policyVersion'] ?? '')}）；
          支付从钱包扣款并立即确认出票。同一订单只有一个活动支付意图，重复点击不会双扣。
        </p>
        {b.status === 'PAYMENT_PENDING' && (
          <button onClick={pay} disabled={paying}>
            {paying ? '处理中…' : '发起支付'}
          </button>
        )}
        {b.status === 'CONFIRMED' && (
          <p className="ok-text">
            出票成功！<Link to={`/bookings/${b.id}`}>查看订单与票券 →</Link>
          </p>
        )}
        {['EXPIRED', 'PAYMENT_FAILED', 'CANCELLED_BEFORE_PAYMENT'].includes(b.status) && (
          <p className="error-text">订单状态：{b.status}，库存与限购已释放。</p>
        )}
        {error && <p className="error-text">{error}</p>}
      </div>

      <div className="card">
        <h3>订单快照</h3>
        <pre className="ticket-token">{JSON.stringify(b.priceSnapshot, null, 2)}</pre>
        <p className="muted">订单创建时间：{formatTime(b.expiresAt)} 前有效（服务端 expiresAt）</p>
      </div>
    </>
  )
}
