import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, useParams } from 'react-router-dom'
import { api, ApiError, formatTime } from '../api'
import { AttendeeRow } from '../types'
import { EmptyState, ErrorNote, TicketStatusBadge } from '../ui/Badges'
import { Field, fieldAria } from '../ui/Field'
import { useToast } from '../ui/Toast'

export function OrganiserAttendeesPage() {
  const { t } = useTranslation()
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
      .catch((e) => setError(e instanceof ApiError ? e.message : t('common.loadFailed')))
  }, [id, t])

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
      notify(t('organiser.checkOk'), 'success')
    } catch (err) {
      const message = err instanceof ApiError ? err.message : t('common.failed')
      setError(message)
      notify(t('organiser.checkFailed', { message }), 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page">
      <nav className="crumbs">
        <NavLink to="/organiser/events">{t('organiser.events')}</NavLink>
        <span aria-hidden>/</span>
        <span>{t('organiser.attendees')}</span>
      </nav>

      <header className="page-head">
        <div>
          <h1>{t('organiser.attendeesTitle')}</h1>
          <p className="muted">{t('organiser.attendeesSub')}</p>
        </div>
        <a className="btn-secondary btn-link" href={`/api/organiser/events/${id}/attendees.csv`}>
          {t('organiser.exportCsv')}
        </a>
      </header>

      <div className="stat-grid stat-grid-compact">
        <div className="stat-card">
          <p className="stat-label">{t('organiser.attendeeCount')}</p>
          <p className="stat-value">{stats.total}</p>
        </div>
        <div className="stat-card stat-accent">
          <p className="stat-label">{t('organiser.checkedIn')}</p>
          <p className="stat-value">{stats.checkedIn}</p>
        </div>
        <div className="stat-card">
          <p className="stat-label">{t('organiser.notIn')}</p>
          <p className="stat-value">{stats.pending}</p>
        </div>
      </div>

      <form className="card check-in-card" onSubmit={checkIn}>
        <Field id="check-code" label={t('organiser.checkCode')} hint={t('organiser.checkHint')}>
          <input
            {...fieldAria('check-code', undefined, 'hint')}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder={t('organiser.checkPlaceholder')}
            autoComplete="off"
          />
        </Field>
        <button type="submit" className="btn-primary" disabled={busy}>
          {busy ? t('common.processing') : t('organiser.checkIn')}
        </button>
        <ErrorNote message={error} />
      </form>

      <div className="search-row toolbar">
        <input
          className="search"
          placeholder={t('organiser.filterAttendees')}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          aria-label={t('organiser.filterAria')}
        />
      </div>

      {visible.length === 0 ? (
        <EmptyState title={t('organiser.noAttendeesTitle')} hint={t('organiser.noAttendeesHint')} />
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">{t('organiser.colGuest')}</th>
                <th scope="col">{t('organiser.colOrder')}</th>
                <th scope="col">{t('organiser.colTicket')}</th>
                <th scope="col">{t('organiser.colStatus')}</th>
                <th scope="col">{t('organiser.colCheckedAt')}</th>
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
