import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router-dom'
import { api } from '../api'
import { EventTicket } from '../components/EventTicket'
import { EventVo, PageVo } from '../types'
import { EmptyState } from '../ui/Badges'
import { SkeletonGrid } from '../ui/Skeleton'

export function FavouritesPage() {
  const { t } = useTranslation()
  const [items, setItems] = useState<EventVo[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api<PageVo<EventVo>>('GET', '/api/favourites')
      .then((page) => setItems(page?.records ?? []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false))
  }, [])

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>{t('favourites.pageTitle')}</h1>
          <p className="muted">{t('favourites.sub')}</p>
        </div>
      </header>
      {loading ? (
        <SkeletonGrid count={3} label={t('favourites.loading')} />
      ) : items.length === 0 ? (
        <EmptyState
          title={t('favourites.emptyTitle')}
          hint={t('favourites.emptyHint')}
          action={
            <NavLink to="/" className="btn-primary btn-link">
              {t('favourites.goDiscover')}
            </NavLink>
          }
        />
      ) : (
        <div className="grid">
          {items.map((event) => (
            <EventTicket key={event.id} event={event} />
          ))}
        </div>
      )}
    </div>
  )
}
