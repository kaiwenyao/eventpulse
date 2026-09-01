import { Navigate, Route, Routes } from 'react-router-dom'
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
import { EventDetailPage } from './pages/EventDetailPage'
import { EventsPage } from './pages/EventsPage'
import { FavouritesPage } from './pages/FavouritesPage'
import { LoginPage } from './pages/LoginPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { EmptyState } from './ui/Badges'
import { SkeletonGrid } from './ui/Skeleton'
import { ToastProvider } from './ui/Toast'

/** Organiser-only shell: authenticated non-organisers get an explanation, not a 404. */
function OrganiserGate() {
  const { user } = useAuth()
  if (user?.role !== 'ORGANISER') {
    return <EmptyState title="没有主办方权限" hint="请使用主办方账号登录后再进入工作台。" />
  }
  return <OrganiserLayout />
}

function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="site-footer-inner">
        <p className="brand">
          Event<span>Pulse</span>
        </p>
        <p className="muted small">城市活动预订演示系统 · Spring Boot + Kafka + React</p>
        <p className="muted small">© {new Date().getFullYear()} EventPulse</p>
      </div>
    </footer>
  )
}

export default function App() {
  const { user, ready } = useAuth()

  if (!ready) {
    return (
      <div className="container">
        <SkeletonGrid count={3} label="正在准备会话" />
      </div>
    )
  }

  const requireUser = (element: React.ReactNode) => (user ? element : <Navigate to="/login" replace />)

  return (
    <ToastProvider>
      <TopBar />
      <div className="container">
        <Routes>
          <Route path="/" element={<EventsPage />} />
          <Route path="/events/:id" element={<EventDetailPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/bookings" element={requireUser(<BookingsPage />)} />
          <Route path="/bookings/:id" element={requireUser(<BookingDetailPage />)} />
          <Route path="/favourites" element={requireUser(<FavouritesPage />)} />
          <Route path="/notifications" element={requireUser(<NotificationsPage />)} />
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
            element={<EmptyState title="页面不存在" hint="链接可能已经失效，回到首页继续浏览活动。" />}
          />
        </Routes>
      </div>
      <SiteFooter />
    </ToastProvider>
  )
}
