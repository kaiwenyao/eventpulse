import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api'
import { EventTicket } from '../components/EventTicket'
import { CATEGORIES, EventVo } from '../types'
import { EmptyState } from '../ui/Badges'
import { SkeletonGrid } from '../ui/Skeleton'
import { AiDiscoveryAssistant } from '../components/AiDiscoveryAssistant'

type DiscoveryMode = 'list' | 'nearby'

/** Berlin city centre — the demo origin for the "附近" radius query; the seeder clusters most venues there. */
const NEARBY_ORIGIN = { lat: 52.52, lng: 13.405, radiusKm: 30 }

function buildPath(mode: DiscoveryMode, params: URLSearchParams) {
  if (mode === 'nearby') {
    return `/api/events/nearby?lat=${NEARBY_ORIGIN.lat}&lng=${NEARBY_ORIGIN.lng}&radiusKm=${NEARBY_ORIGIN.radiusKm}`
  }
  return `/api/events${params.size ? `?${params}` : ''}`
}

export function EventsPage() {
  const { t } = useTranslation()
  const [events, setEvents] = useState<EventVo[]>([])
  const [loading, setLoading] = useState(true)
  const [q, setQ] = useState('')
  const [cat, setCat] = useState('')
  const [city, setCity] = useState('')
  const [sort, setSort] = useState('startsAt')
  const [mode, setMode] = useState<DiscoveryMode>('list')
  const [assistantOpen, setAssistantOpen] = useState(false)

  useEffect(() => {
    const params = new URLSearchParams()
    if (q) params.set('q', q)
    if (cat) params.set('category', cat)
    if (city) params.set('city', city)
    if (sort) params.set('sort', sort)
    let cancelled = false
    api<EventVo[]>('GET', buildPath(mode, params))
      .then((data) => {
        if (!cancelled) setEvents(Array.isArray(data) ? data : [])
      })
      .catch(() => {
        if (!cancelled) setEvents([])
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [q, cat, city, sort, mode])

  const stats = useMemo(() => {
    const cities = new Set(events.map((e) => e.city).filter(Boolean))
    const remaining = events.reduce((sum, e) => sum + Math.max(e.capacity - e.sold, 0), 0)
    return { count: events.length, cities: cities.size, remaining }
  }, [events])

  return (
    <div>
      <section className="hero">
        <div className="hero-copy">
          <p className="eyebrow">{t('events.eyebrow')}</p>
          <h1>{t('events.hero')}</h1>
          <p className="muted hero-sub">{t('events.sub')}</p>
        </div>
        <dl className="hero-stats">
          <div>
            <dt>{t('events.onSale')}</dt>
            <dd>{stats.count}</dd>
          </div>
          <div>
            <dt>{t('events.cities')}</dt>
            <dd>{stats.cities}</dd>
          </div>
          <div>
            <dt>{t('events.ticketsLeft')}</dt>
            <dd>{stats.remaining}</dd>
          </div>
        </dl>
      </section>

      <div className="search-row">
        <input
          className="search"
          placeholder={t('events.search')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          aria-label={t('events.searchAria')}
        />
        <div className="chips" role="group" aria-label={t('events.filterCategory')}>
          <button className={`chip ${cat === '' ? 'active' : ''}`} onClick={() => setCat('')}>
            {t('common.all')}
          </button>
          {CATEGORIES.map((c) => (
            <button key={c.key} className={`chip ${cat === c.key ? 'active' : ''}`} onClick={() => setCat(c.key)}>
              {t(`category.${c.key}`)}
            </button>
          ))}
        </div>
        <input
          className="search search-city"
          placeholder={t('events.city')}
          value={city}
          onChange={(e) => setCity(e.target.value)}
          aria-label={t('events.city')}
        />
        <select aria-label={t('events.sort')} value={sort} onChange={(e) => setSort(e.target.value)}>
          <option value="startsAt">{t('events.sortStarts')}</option>
          <option value="price">{t('events.sortPrice')}</option>
          <option value="sold">{t('events.sortSold')}</option>
        </select>
        <div className="chips chips-loose" role="group" aria-label={t('events.modeAria')}>
          <button
            className={`chip ${mode === 'nearby' ? 'active' : ''}`}
            onClick={() => setMode(mode === 'nearby' ? 'list' : 'nearby')}
          >
            {t('events.nearby')}
          </button>
          <button
            className={`chip ${assistantOpen ? 'active' : ''}`}
            aria-expanded={assistantOpen}
            onClick={() => setAssistantOpen(!assistantOpen)}
          >
            {t('ai.discovery.entry')}
          </button>
        </div>
      </div>

      {assistantOpen && <AiDiscoveryAssistant onClose={() => setAssistantOpen(false)} />}

      {loading ? (
        <SkeletonGrid label={t('events.loading')} />
      ) : events.length === 0 ? (
        <EmptyState title={t('events.emptyTitle')} hint={t('events.emptyHint')} />
      ) : (
        <div className="grid">
          {events.map((event) => (
            <EventTicket key={event.id} event={event} />
          ))}
        </div>
      )}
    </div>
  )
}
