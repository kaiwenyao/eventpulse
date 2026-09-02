import { formatMoney, formatTime } from '../api'
import { localInputToIso } from '../lib/datetime'
import { CategoryPill, SoldBar } from '../ui/Badges'
import { ArrowRightIcon, ClockIcon } from '../ui/Icons'
import { EventFormState, publishWarnings, yuanToCents } from './eventForm'

/**
 * Live mirror of the discovery card an audience will see, plus a publish
 * checklist. Showing the real card while editing is what stops "publish, look,
 * go back and fix" round-trips.
 */
export function EventFormPreview({ form }: { form: EventFormState }) {
  const cents = yuanToCents(form.priceYuan) ?? 0
  const capacity = Number(form.capacity) || 0
  const startsAtIso = localInputToIso(form.startsAt)
  const warnings = publishWarnings(form)

  return (
    <aside className="form-preview">
      <p className="eyebrow">实时预览</p>
      <div className="ticket ticket-static">
        <div
          className={`ticket-cover ${form.coverUrl ? '' : 'ticket-cover-empty'}`}
          style={form.coverUrl ? { backgroundImage: `url(${form.coverUrl})` } : undefined}
          aria-hidden
        />
        <div className="ticket-main">
          <div className="ticket-head">
            <CategoryPill category={form.category || 'unknown'} />
            <span className="ticket-city">{form.city || '待填写'}</span>
          </div>
          <h2 className="ticket-title">{form.title || '未命名活动'}</h2>
          {form.summary && <p className="ticket-summary">{form.summary}</p>}
          <p className="ticket-time">
            <ClockIcon />
            {startsAtIso ? formatTime(startsAtIso) : '待选择时间'}
          </p>
          <SoldBar sold={0} capacity={capacity} />
        </div>
        <div className="ticket-stub">
          <span className="stub-price">{cents === 0 ? '免费' : formatMoney(cents)}</span>
          <span className="stub-caption">
            预订
            <ArrowRightIcon className="stub-arrow" />
          </span>
        </div>
      </div>

      <div className="checklist">
        <p className="checklist-title">发布前检查</p>
        {warnings.length === 0 ? (
          <p className="ok-text">全部就绪，可以直接发布。</p>
        ) : (
          <ul>
            {warnings.map((warning) => (
              <li key={warning} className="muted small">
                {warning}
              </li>
            ))}
          </ul>
        )}
      </div>
    </aside>
  )
}
