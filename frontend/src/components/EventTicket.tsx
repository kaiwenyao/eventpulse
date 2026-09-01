import { NavLink } from 'react-router-dom'
import { formatMoney, formatTime } from '../api'
import { EventVo } from '../types'
import { CategoryPill, SoldBar } from '../ui/Badges'
import { ArrowRightIcon, ClockIcon, PinIcon } from '../ui/Icons'

/**
 * The discovery card: a perforated ticket whose stub carries the price and the
 * call to action. Every element is real data — category, city, start time, and
 * the sold-ratio meter — so the card is scannable rather than decorative.
 */
export function EventTicket({ event }: { event: EventVo }) {
  return (
    <NavLink to={`/events/${event.id}`} className="ticket">
      <div className="ticket-main">
        {event.coverUrl && (
          <div className="ticket-cover" style={{ backgroundImage: `url(${event.coverUrl})` }} aria-hidden />
        )}
        <div className="ticket-head">
          <CategoryPill category={event.category} />
          <span className="ticket-city">
            <PinIcon />
            {event.city}
          </span>
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
        <span className="stub-price">{event.priceCents === 0 ? '免费' : formatMoney(event.priceCents)}</span>
        <span className="stub-caption">预订</span>
        <ArrowRightIcon className="stub-arrow" />
      </div>
    </NavLink>
  )
}
