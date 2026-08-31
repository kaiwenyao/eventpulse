import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, formatMoney, formatTime, newIdempotencyKey, ApiError } from '../api'
import { useAuth } from '../auth'

interface Tier {
  id: string
  name: string
  unitPriceMinor: number
  currency: string
  saleStartAt: string
  saleEndAt: string
  perUserLimit: number
  status: string
  capacity: number | null
  available: number | null
  sold: number | null
}

interface EventDetail {
  id: string
  title: string
  description: string | null
  category: string
  status: string
  startsAt: string
  endsAt: string
  ageRequirement: number | null
  policyVersion: number
  policy: Record<string, unknown>
  venueName: string | null
  city: string | null
  organiserName: string
  tiers: Tier[]
}

export default function EventDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const [selectedTier, setSelectedTier] = useState<string | null>(null)
  const [quantity, setQuantity] = useState(1)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)

  const detail = useQuery({
    queryKey: ['event', id],
    queryFn: () => api<EventDetail>('GET', `/api/v1/events/${id}`),
  })

  const ageNote = detail.data?.ageRequirement == null
    ? '年龄资格未知：结算时需确认'
    : `需已验证年龄 ≥ ${detail.data.ageRequirement} 岁`

  async function saveEvent() {
    if (!id || saved) return
    setError('')
    try {
      await api('PUT', `/api/v1/me/saved-events/${id}`)
      setSaved(true)
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '网络错误')
    }
  }

  async function book() {
    if (!selectedTier) return
    setBusy(true)
    setError('')
    try {
      const booking = await api<{ id: string; expiresAt: string }>('POST', '/api/v1/bookings',
        { eventId: id, tierId: selectedTier, quantity, ageConfirmed: true },
        { idempotencyKey: newIdempotencyKey() })
      navigate(`/checkout/${booking.id}`)
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '网络错误')
    } finally {
      setBusy(false)
    }
  }

  if (detail.isLoading) return <p className="muted">加载中…</p>
  if (!detail.data) return <p className="muted">活动不存在或不可见。</p>
  const d = detail.data

  return (
    <>
      <div className="card">
        <div className="row spread">
          <h2 style={{ margin: 0 }}>{d.title}</h2>
          <span className="badge accent">{d.category}</span>
        </div>
        <p className="muted">{formatTime(d.startsAt)} — {formatTime(d.endsAt)}</p>
        <p>{d.description}</p>
        <p className="muted">
          {d.venueName} {d.city ? `· ${d.city}` : ''} · 主办方 {d.organiserName}
        </p>
        {user && (
          <button className="secondary" onClick={saveEvent} disabled={saved}>
            {saved ? '已收藏' : '收藏活动'}
          </button>
        )}
        <p>
          <span className={d.ageRequirement == null ? 'badge warn' : 'badge'}>{ageNote}</span>{' '}
          <span className="badge">取消政策 v{d.policyVersion}</span>
        </p>
      </div>

      <div className="card">
        <h3>票档</h3>
        <table>
          <thead>
            <tr>
              <th>票档</th>
              <th>价格</th>
              <th>余票</th>
              <th>限购</th>
              <th>状态</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {d.tiers.map((t) => {
              const saleOpen = new Date(t.saleStartAt) <= new Date() && new Date(t.saleEndAt) >= new Date()
              return (
                <tr key={t.id}>
                  <td>{t.name}</td>
                  <td className="big-price">{formatMoney(t.unitPriceMinor, t.currency)}</td>
                  <td>{t.available ?? '—'}</td>
                  <td>{t.perUserLimit} 张</td>
                  <td>
                    <span className={saleOpen ? 'badge success' : 'badge'}>
                      {t.status === 'ACTIVE' ? (saleOpen ? '在售' : '未开售/已停售') : t.status}
                    </span>
                  </td>
                  <td>
                    {user && saleOpen && (
                      <button className="secondary" onClick={() => setSelectedTier(t.id)}>
                        选择
                      </button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
        {!user && <p className="muted">登录后可预订。</p>}
      </div>

      {user && selectedTier && (
        <div className="card">
          <h3>确认预订</h3>
          <p className="muted">
            服务端将重新校验销售窗口、限购、库存与价格；过期时间由数据库时钟生成。
            请求携带高熵幂等键：重试会复用同一预订，不会重复扣减库存。
          </p>
          <div className="row">
            <label>数量</label>
            <input
              type="number"
              min={1}
              max={10}
              value={quantity}
              onChange={(e) => setQuantity(Math.max(1, Math.min(10, Number(e.target.value))))}
              style={{ width: 90 }}
            />
            <button onClick={book} disabled={busy}>
              {busy ? '创建中…' : '创建预订'}
            </button>
          </div>
          {error && <p className="error-text">{error}</p>}
        </div>
      )}
      {user && !selectedTier && error && <p className="error-text">{error}</p>}
    </>
  )
}
