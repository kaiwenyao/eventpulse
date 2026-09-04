import { Navigate, Route, Routes } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from './auth'
import { TopBar } from './components/TopBar'
import { OrganiserAnalyticsPage } from './organiser/OrganiserAnalyticsPage'
import { OrganiserAttendeesPage } from './organiser/OrganiserAttendeesPage'
import { OrganiserDashboardPage } from './organiser/OrganiserDashboardPage'
import { OrganiserDetailPage } from './organiser/OrganiserDetailPage'
import { OrganiserEventsPage } from './organiser/OrganiserEventsPage'
import { OrganiserFormPage } from './organiser/OrganiserFormPage'
import { OrganiserLayout } from './organiser/OrganiserLayout'
import { BookingDetailPage } from './pages/BookingDetailPage'
import { BookingsPage } from './pages/BookingsPage'
import { CartPage } from './pages/CartPage'
import { EventDetailPage } from './pages/EventDetailPage'
import { EventsPage } from './pages/EventsPage'
import { FavouritesPage } from './pages/FavouritesPage'
import { LoginPage } from './pages/LoginPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { ProfilePage } from './pages/ProfilePage'
import { WalletLedgerPage } from './pages/WalletLedgerPage'
import { EmptyState } from './ui/Badges'
import { SkeletonGrid } from './ui/Skeleton'
import { ToastProvider } from './ui/Toast'

/** Organiser-only shell: authenticated non-organisers get an explanation, not a 404. */
function OrganiserGate() {
  const { user } = useAuth()
  const { t } = useTranslation()
  if (user?.role !== 'ORGANISER') {
    return <EmptyState title={t('app.noOrganiserTitle')} hint={t('app.noOrganiserHint')} />
  }
  return <OrganiserLayout />
}

function SiteFooter() {
  const { t } = useTranslation()
  return (
    <footer className="site-footer">
      <div className="site-footer-inner">
        <p className="brand">
          Event<span>Pulse</span>
        </p>
        <p className="muted small">{t('footer.tagline')}</p>
        <p className="muted small">© {new Date().getFullYear()} EventPulse</p>
      </div>
    </footer>
  )
}

function NotFound() {
  const { t } = useTranslation()
  return <EmptyState title={t('app.notFoundTitle')} hint={t('app.notFoundHint')} />
}

export default function App() {
  const { user, ready } = useAuth()
  const { t } = useTranslation()

  if (!ready) {
    return (
      <div className="container">
        <SkeletonGrid count={3} label={t('app.preparingSession')} />
      </div>
    )
  }

  const requireUser = (element: React.ReactNode) => (user ? element : <Navigate to="/login" replace />)
  // A signed-in visitor landing on /login directly is bounced back to the
  // discovery page — the auth form is only for guests. `replace` keeps the
  // login URL out of history so Back never re-triggers the redirect.
  const requireGuest = (element: React.ReactNode) => (user ? <Navigate to="/" replace /> : element)

  return (
    <ToastProvider>
      <TopBar />
      <div className="container">
        <Routes>
          <Route path="/" element={<EventsPage />} />
          <Route path="/events/:id" element={<EventDetailPage />} />
          <Route path="/login" element={requireGuest(<LoginPage />)} />
          <Route path="/bookings" element={requireUser(<BookingsPage />)} />
          <Route path="/bookings/:id" element={requireUser(<BookingDetailPage />)} />
          <Route path="/cart" element={requireUser(<CartPage />)} />
          <Route path="/wallet/ledger" element={requireUser(<WalletLedgerPage />)} />
          <Route path="/favourites" element={requireUser(<FavouritesPage />)} />
          <Route path="/notifications" element={requireUser(<NotificationsPage />)} />
          <Route path="/profile" element={requireUser(<ProfilePage />)} />
          <Route path="/organiser" element={requireUser(<OrganiserGate />)}>
            <Route index element={<OrganiserDashboardPage />} />
            <Route path="events" element={<OrganiserEventsPage />} />
            <Route path="events/new" element={<OrganiserFormPage />} />
            <Route path="events/:id" element={<OrganiserDetailPage />} />
            <Route path="events/:id/edit" element={<OrganiserFormPage />} />
            <Route path="events/:id/attendees" element={<OrganiserAttendeesPage />} />
            <Route path="analytics" element={<OrganiserAnalyticsPage />} />
          </Route>
          <Route
            path="*"
            element={<NotFound />}
          />
        </Routes>
      </div>
      <SiteFooter />
    </ToastProvider>
  )
}
