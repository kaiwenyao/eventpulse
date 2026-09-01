import { useEffect, useState } from 'react'
import { NavLink, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, formatMoney, formatTime } from '../api'
import { EVENT_STATUSES, EventVo } from '../types'
import { CategoryPill, EmptyState, ErrorNote, EventStatusBadge, SoldBar } from '../ui/Badges'
import { ConfirmDialog } from '../ui/Modal'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'

/** Visual order of the happy path; CANCELLED is terminal and sits outside it. */
const LIFECYCLE = EVENT_STATUSES.filter((s) => s.key !== 'CANCELLED')

type PendingAction = 'cancel' | 'delete' | 'archive' | null

function LifecycleStepper({ status }: { status: string }) {
  const activeIndex = LIFECYCLE.findIndex((s) => s.key === status)
  const cancelled = status === 'CANCELLED'
  return (
    <ol className={`stepper-track${cancelled ? ' is-cancelled' : ''}`} aria-label="活动生命周期">
      {LIFECYCLE.map((step, index) => {
        const state = cancelled ? 'idle' : index < activeIndex ? 'done' : index === activeIndex ? 'current' : 'idle'
        return (
          <li key={step.key} className={`step step-${state}`} aria-current={state === 'current' ? 'step' : undefined}>
            <span className="step-dot" aria-hidden />
            {step.label}
          </li>
        )
      })}
      {cancelled && <li className="step step-cancelled">已取消</li>}
    </ol>
  )
}

export function OrganiserDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { notify } = useToast()
  const [event, setEvent] = useState<EventVo | null>(null)
  const [error, setError] = useState('')
  const [pending, setPending] = useState<PendingAction>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api<EventVo>('GET', `/api/organiser/events/${id}`)
      .then(setEvent)
      .catch((e) => setError(e instanceof ApiError ? e.message : '加载失败'))
  }, [id])

  async function run(action: () => Promise<unknown>, successMessage: string, stay = false) {
    setBusy(true)
    try {
      await action()
      notify(successMessage, 'success')
      if (!stay) navigate('/organiser/events')
    } catch (e) {
      const message = e instanceof ApiError ? e.message : '操作失败'
      setError(message)
      notify(`操作失败：${message}`, 'error')
    } finally {
      setBusy(false)
      setPending(null)
    }
  }

  if (error && !event) return <EmptyState title={error} hint="只能查看自己的活动。" />
  if (!event) return <SkeletonCard />

  const isDraft = event.status === 'DRAFT'
  const isLive = event.status === 'PUBLISHED' || event.status === 'ONGOING'
  const isClosed = event.status === 'FINISHED' || event.status === 'CANCELLED'

  return (
    <div className="page">
      <nav className="crumbs">
        <NavLink to="/organiser">工作台</NavLink>
        <span aria-hidden>/</span>
        <NavLink to="/organiser/events">活动管理</NavLink>
        <span aria-hidden>/</span>
        <span>{event.title}</span>
      </nav>

      <header className="page-head">
        <div>
          <div className="row detail-top">
            <CategoryPill category={event.category} />
            <EventStatusBadge status={event.status} />
          </div>
          <h1>{event.title}</h1>
          <p className="muted">
            {event.city} · {formatTime(event.startsAt)}
          </p>
        </div>
      </header>

      <LifecycleStepper status={event.status} />

      <ErrorNote message={error} />

      <div className="card fact-card">
        <dl className="fact-grid">
          <div>
            <dt>票价</dt>
            <dd>{event.priceCents === 0 ? '免费' : formatMoney(event.priceCents)}</dd>
          </div>
          <div>
            <dt>已售 / 容量</dt>
            <dd>
              {event.sold}/{event.capacity}
            </dd>
          </div>
          <div>
            <dt>场地</dt>
            <dd>{event.venueName || '未填写'}</dd>
          </div>
          <div>
            <dt>版本</dt>
            <dd>v{event.version ?? 0}</dd>
          </div>
        </dl>
        <SoldBar sold={event.sold} capacity={event.capacity} />
      </div>

      {event.cancellationReason && (
        <div className="callout callout-error">
          <p className="callout-title">活动已取消</p>
          <p className="muted">{event.cancellationReason}</p>
        </div>
      )}

      <div className="row action-rail">
        {isDraft && (
          <button
            className="btn-primary"
            disabled={busy}
            onClick={() => run(() => api('POST', `/api/organiser/events/${event.id}/publish`), '活动已发布')}
          >
            发布
          </button>
        )}
        <NavLink to={`/organiser/events/${event.id}/edit`} className="btn-secondary btn-link">
          编辑
        </NavLink>
        <NavLink to={`/organiser/events/${event.id}/attendees`} className="btn-secondary btn-link">
          参与者
        </NavLink>
        <button
          className="btn-secondary"
          disabled={busy}
          onClick={() => run(() => api('POST', `/api/organiser/events/${event.id}/duplicate`), '已复制为新草稿')}
        >
          复制
        </button>
        {isLive && (
          <button className="btn-danger" disabled={busy} onClick={() => setPending('cancel')}>
            取消活动
          </button>
        )}
        {isClosed && (
          <button className="btn-secondary" disabled={busy} onClick={() => setPending('archive')}>
            归档
          </button>
        )}
        {isDraft && (
          <button className="btn-danger" disabled={busy} onClick={() => setPending('delete')}>
            删除草稿
          </button>
        )}
      </div>

      <ConfirmDialog
        open={pending === 'cancel'}
        title="取消这场活动？"
        description="已购票的观众会收到取消通知并进入退款流程，此操作不可撤销。"
        reasonLabel="取消原因"
        reasonPlaceholder="例如：场地临时不可用"
        confirmLabel="确认取消活动"
        tone="danger"
        busy={busy}
        onCancel={() => setPending(null)}
        onConfirm={(reason) => run(() => api('POST', `/api/organiser/events/${event.id}/cancel`, { reason }), '活动已取消')}
      />

      <ConfirmDialog
        open={pending === 'archive'}
        title="归档这场活动？"
        description="归档后活动不再出现在管理列表的默认视图中，数据仍会保留。"
        confirmLabel="确认归档"
        busy={busy}
        onCancel={() => setPending(null)}
        onConfirm={() => run(() => api('POST', `/api/organiser/events/${event.id}/archive`, { note: '归档' }), '活动已归档')}
      />

      <ConfirmDialog
        open={pending === 'delete'}
        title="删除这份草稿？"
        description="草稿删除后无法恢复。"
        confirmLabel="确认删除"
        tone="danger"
        busy={busy}
        onCancel={() => setPending(null)}
        onConfirm={() => run(() => api('DELETE', `/api/organiser/events/${event.id}`), '草稿已删除')}
      />
    </div>
  )
}
