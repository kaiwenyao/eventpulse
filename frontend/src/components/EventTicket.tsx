import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { formatMoney, formatTime } from '../api'
import { EventVo } from '../types'
import { CategoryPill, SoldBar } from '../ui/Badges'
import { ArrowRightIcon, ClockIcon } from '../ui/Icons'

/**
 * The discovery card: a monochrome cover, the facts stacked underneath, and a
 * footer bar carrying the price against the booking CTA. Every element is real
 * data — category, city, start time, and the sold-ratio meter — so the card is
 * scannable rather than decorative.
 */
export function EventTicket({ event }: { event: EventVo }) {
  const { t } = useTranslation()
  return (
    <NavLink to={`/events/${event.id}`} className="ticket">
      <div
        className={`ticket-cover ${event.coverUrl ? '' : 'ticket-cover-empty'}`}
        style={event.coverUrl ? { backgroundImage: `url(${event.coverUrl})` } : undefined}
        aria-hidden
      />
      <div className="ticket-main">
        <div className="ticket-head">
          <CategoryPill category={event.category} />
          <span className="ticket-city">{event.city}</span>
        </div>
        <h2 className="ticket-title">{event.title}</h2>
        {event.summary && <p className="ticket-summary">{event.summary}</p>}
        <p className="ticket-time">
          <ClockIcon />
          {formatTime(event.startsAt)}
        </p>
        <SoldBar sold={event.sold} capacity={event.capacity} />
      </div>
      <div className="ticket-stub">
        <span className="stub-price">{event.priceCents === 0 ? t('common.free') : formatMoney(event.priceCents)}</span>
        <span className="stub-caption">
          {t('events.book')}
          <ArrowRightIcon className="stub-arrow" />
        </span>
      </div>
    </NavLink>
  )
}
