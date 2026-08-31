import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api'

interface FunnelRow {
  eventId: string
  title: string
  status: string
  startsAt: string
  views: number
  saves: number
  bookingsCreated: number
  bookingsConfirmed: number
  ticketsIssued: number
}

export default function Organiser() {
  const queryClient = useQueryClient()
  const [error, setError] = useState('')
  const [okText, setOkText] = useState('')
  const [form, setForm] = useState({
    title: '', description: '', category: 'music', city: '上海', venueName: '',
    lat: '31.23', lng: '121.47', startsAt: '', endsAt: '',
    tierName: '标准票', price: '10000', capacity: '100', perUserLimit: '5',
  })

  const funnel = useQuery({
    queryKey: ['organiser', 'funnel'],
    queryFn: () => api<FunnelRow[]>('GET', '/api/v1/organiser/funnel'),
  })

  async function createEvent() {
    setError('')
    setOkText('')
    try {
      const venue = {
        name: form.venueName || '默认场地', city: form.city, lat: Number(form.lat), lng: Number(form.lng),
      }
      const body = {
        title: form.title,
        description: form.description,
        category: form.category,
        startsAt: new Date(form.startsAt).toISOString(),
        endsAt: new Date(form.endsAt).toISOString(),
        policy: { cancellable: true, cancellationDeadlineHoursBeforeStart: 24, resaleAllowed: false, version: 1 },
        venue,
        tiers: [{
          name: form.tierName, currency: 'CNY', unitPriceMinor: Number(form.price),
          saleStartAt: new Date(Date.now() - 3600_000).toISOString(),
          saleEndAt: new Date(form.endsAt).toISOString(),
          perUserLimit: Number(form.perUserLimit), capacity: Number(form.capacity),
        }],
      }
      await api('POST', '/api/v1/organiser/events', body)
      // publish immediately for the demo
      const list = await api<{ items: Array<{ id: string; title: string }> }>(
        'GET', `/api/v1/events?q=${encodeURIComponent(form.title)}`)
      const created = list.items.find((i) => i.title === form.title)
      if (created) {
        await api('POST', `/api/v1/organiser/events/${created.id}/publish`, {})
      }
      setOkText('活动已创建并发布')
      queryClient.invalidateQueries({ queryKey: ['organiser'] })
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : '网络错误')
    }
  }

  return (
    <>
      <div className="card">
        <h2>主办方后台</h2>
        {funnel.data && funnel.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>活动</th><th>状态</th><th>浏览</th><th>收藏</th><th>订单</th><th>成交</th><th>出票</th>
              </tr>
            </thead>
            <tbody>
              {funnel.data.map((row) => (
                <tr key={row.eventId}>
                  <td>{row.title}</td>
                  <td>{row.status}</td>
                  <td>{row.views}</td>
                  <td>{row.saves}</td>
                  <td>{row.bookingsCreated}</td>
                  <td>{row.bookingsConfirmed}</td>
                  <td>{row.ticketsIssued}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {funnel.data?.length === 0 && <p className="muted">还没有活动。</p>}
      </div>

      <div className="card">
        <h3>创建活动（草稿 → 发布）</h3>
        <div className="row">
          <div style={{ flex: 2 }}>
            <label>标题</label>
            <input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
          </div>
          <div style={{ flex: 1 }}>
            <label>类别</label>
            <select value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}>
              <option value="music">音乐</option>
              <option value="tech">科技</option>
              <option value="sports">运动</option>
              <option value="art">艺术</option>
            </select>
          </div>
        </div>
        <label>描述</label>
        <input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
        <div className="row">
          <div style={{ flex: 1 }}>
            <label>城市</label>
            <input value={form.city} onChange={(e) => setForm({ ...form, city: e.target.value })} />
          </div>
          <div style={{ flex: 1 }}>
            <label>场地</label>
            <input value={form.venueName} onChange={(e) => setForm({ ...form, venueName: e.target.value })} />
          </div>
          <div style={{ flex: 1 }}>
            <label>纬度</label>
            <input value={form.lat} onChange={(e) => setForm({ ...form, lat: e.target.value })} />
          </div>
          <div style={{ flex: 1 }}>
            <label>经度</label>
            <input value={form.lng} onChange={(e) => setForm({ ...form, lng: e.target.value })} />
          </div>
        </div>
        <div className="row">
          <div style={{ flex: 1 }}>
            <label>开始时间</label>
            <input type="datetime-local" value={form.startsAt}
              onChange={(e) => setForm({ ...form, startsAt: e.target.value })} />
          </div>
          <div style={{ flex: 1 }}>
            <label>结束时间</label>
            <input type="datetime-local" value={form.endsAt}
              onChange={(e) => setForm({ ...form, endsAt: e.target.value })} />
          </div>
        </div>
        <h4>票档</h4>
        <div className="row">
          <div style={{ flex: 1 }}>
            <label>名称</label>
            <input value={form.tierName} onChange={(e) => setForm({ ...form, tierName: e.target.value })} />
          </div>
          <div style={{ flex: 1 }}>
            <label>价格（分）</label>
            <input value={form.price} onChange={(e) => setForm({ ...form, price: e.target.value })} />
          </div>
          <div style={{ flex: 1 }}>
            <label>库存</label>
            <input value={form.capacity} onChange={(e) => setForm({ ...form, capacity: e.target.value })} />
          </div>
          <div style={{ flex: 1 }}>
            <label>每人限购</label>
            <input value={form.perUserLimit}
              onChange={(e) => setForm({ ...form, perUserLimit: e.target.value })} />
          </div>
        </div>
        <button onClick={createEvent}>创建并发布</button>
        {okText && <p className="ok-text">{okText}</p>}
        {error && <p className="error-text">{error}</p>}
        <p className="muted" style={{ fontSize: 12 }}>
          容量调整（PATCH /api/v1/organiser/tiers/{`{id}`}/inventory，If-Match 版本）请走 API；
          调整下限不得低于 reserved + sold + withheld。
        </p>
      </div>
    </>
  )
}
