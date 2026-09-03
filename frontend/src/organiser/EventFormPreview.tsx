import { useTranslation } from 'react-i18next'
import { formatMoney, formatTime } from '../api'
import { localInputToIso } from '../lib/datetime'
import { CategoryPill, SoldBar } from '../ui/Badges'
import { ArrowRightIcon, ClockIcon } from '../ui/Icons'
import { EventFormState, publishWarnings, euroToCents } from './eventForm'

/**
 * Live mirror of the discovery card an audience will see, plus a publish
 * checklist. Showing the real card while editing is what stops "publish, look,
 * go back and fix" round-trips.
 */
export function EventFormPreview({ form }: { form: EventFormState }) {
  const { t } = useTranslation()
  const cents = euroToCents(form.priceEuro) ?? 0
  const capacity = Number(form.capacity) || 0
  const startsAtIso = localInputToIso(form.startsAt)
  const warnings = publishWarnings(form)

  return (
    <aside className="form-preview">
      <p className="eyebrow">{t('organiser.preview')}</p>
      <div className="ticket ticket-static">
        <div
          className={`ticket-cover ${form.coverUrl ? '' : 'ticket-cover-empty'}`}
          style={form.coverUrl ? { backgroundImage: `url(${form.coverUrl})` } : undefined}
          aria-hidden
        />
        <div className="ticket-main">
          <div className="ticket-head">
            <CategoryPill category={form.category || 'unknown'} />
            <span className="ticket-city">{form.city || t('organiser.pending')}</span>
          </div>
          <h2 className="ticket-title">{form.title || t('organiser.untitled')}</h2>
          {form.summary && <p className="ticket-summary">{form.summary}</p>}
          <p className="ticket-time">
            <ClockIcon />
            {startsAtIso ? formatTime(startsAtIso) : t('organiser.pickTime')}
          </p>
          <SoldBar sold={0} capacity={capacity} />
        </div>
        <div className="ticket-stub">
          <span className="stub-price">{cents === 0 ? t('common.free') : formatMoney(cents)}</span>
          <span className="stub-caption">
            {t('events.book')}
            <ArrowRightIcon className="stub-arrow" />
          </span>
        </div>
      </div>

      <div className="checklist">
        <p className="checklist-title">{t('organiser.checklist')}</p>
        {warnings.length === 0 ? (
          <p className="ok-text">{t('organiser.allReady')}</p>
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
