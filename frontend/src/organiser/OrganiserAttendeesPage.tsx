import { FormEvent, useEffect, useMemo, useState } from 'react'
import { NavLink, useParams } from 'react-router-dom'
import { api, ApiError, formatTime } from '../api'
import { AttendeeRow } from '../types'
import { EmptyState, ErrorNote, TicketStatusBadge } from '../ui/Badges'
import { Field, fieldAria } from '../ui/Field'
import { useToast } from '../ui/Toast'

export function OrganiserAttendeesPage() {
  const { id } = useParams()
  const { notify } = useToast()
  const [rows, setRows] = useState<AttendeeRow[]>([])
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [filter, setFilter] = useState('')

  useEffect(() => {
    api<AttendeeRow[]>('GET', `/api/organiser/events/${id}/attendees`)
      .then((data) => setRows(Array.isArray(data) ? data : []))
      .catch((e) => setError(e instanceof ApiError ? e.message : '加载失败'))
  }, [id])

  const stats = useMemo(() => {
    const checkedIn = rows.filter((r) => r.status === 'CHECKED_IN').length
    return { total: rows.length, checkedIn, pending: rows.length - checkedIn }
  }, [rows])

  const visible = useMemo(() => {
    const needle = filter.trim().toLowerCase()
    if (!needle) return rows
    return rows.filter((r) => `${r.name} ${r.email}`.toLowerCase().includes(needle))
  }, [rows, filter])

  async function checkIn(event: FormEvent) {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      await api('POST', '/api/organiser/tickets/check-in', { code, source: 'manual' })
      const next = await api<AttendeeRow[]>('GET', `/api/organiser/events/${id}/attendees`)
      setRows(Array.isArray(next) ? next : [])
      setCode('')
      notify('签到成功', 'success')
    } catch (err) {
      const message = err instanceof ApiError ? err.message : '核销失败'
      setError(message)
      notify(`核销失败：${message}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page">
      <nav className="crumbs">
        <NavLink to="/organiser/events">活动管理</NavLink>
        <span aria-hidden>/</span>
        <span>参与者</span>
      </nav>

      <header className="page-head">
        <div>
          <h1>参与者管理</h1>
          <p className="muted">现场扫码或手动输入票码完成核销，导出名单用于签到备份。</p>
        </div>
        <a className="btn-secondary btn-link" href={`/api/organiser/events/${id}/attendees.csv`}>
          导出 CSV
        </a>
      </header>

      <div className="stat-grid stat-grid-compact">
        <div className="stat-card">
          <p className="stat-label">参与者</p>
          <p className="stat-value">{stats.total}</p>
        </div>
        <div className="stat-card stat-accent">
          <p className="stat-label">已入场</p>
          <p className="stat-value">{stats.checkedIn}</p>
        </div>
        <div className="stat-card">
          <p className="stat-label">未入场</p>
          <p className="stat-value">{stats.pending}</p>
        </div>
      </div>

      <form className="card check-in-card" onSubmit={checkIn}>
        <Field id="check-code" label="票码核销" hint="输入或扫描观众票面上的票码，回车即可完成签到。">
          <input
            {...fieldAria('check-code', undefined, 'hint')}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="例如：EP-8F3A-2K9D"
            autoComplete="off"
          />
        </Field>
        <button type="submit" className="btn-primary" disabled={busy}>
          {busy ? '处理中…' : '签到'}
        </button>
        <ErrorNote message={error} />
      </form>

      <div className="search-row toolbar">
        <input
          className="search"
          placeholder="按姓名或邮箱筛选…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          aria-label="筛选参与者"
        />
      </div>

      {visible.length === 0 ? (
        <EmptyState title="还没有参与者" hint="有人下单后，票据会自动出现在这里。" />
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">观众</th>
                <th scope="col">订单</th>
                <th scope="col">票号</th>
                <th scope="col">状态</th>
                <th scope="col">核销时间</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((row) => (
                <tr key={row.ticketId}>
                  <th scope="row">
                    <span className="table-title">{row.name || row.email}</span>
                    <span className="muted small">{row.email}</span>
                  </th>
                  <td className="num">#{row.bookingId}</td>
                  <td className="num">#{row.ticketId}</td>
                  <td>
                    <TicketStatusBadge status={row.status} />
                  </td>
                  <td className="num muted">
                    {row.checkedInAt && row.checkedInAt !== 'null' ? formatTime(row.checkedInAt) : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
