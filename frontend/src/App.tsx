import { Navigate, NavLink, Route, Routes, useNavigate } from 'react-router-dom'
import { useAuth } from './auth'
import Discovery from './pages/Discovery'
import EventDetail from './pages/EventDetail'
import Checkout from './pages/Checkout'
import Orders from './pages/Orders'
import BookingDetail from './pages/BookingDetail'
import Login from './pages/Login'
import Organiser from './pages/Organiser'
import Redeem from './pages/Redeem'
import Admin from './pages/Admin'

function TopBar() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  return (
    <header className="topbar">
      <NavLink to="/" className="brand">
        Event<span>Pulse</span>
      </NavLink>
      <nav>
        <NavLink to="/">发现</NavLink>
        {user && <NavLink to="/orders">我的订单</NavLink>}
        {user && (user.role === 'ORGANISER' || user.role === 'ADMIN') && (
          <>
            <NavLink to="/organiser">主办方</NavLink>
            <NavLink to="/redeem">核销</NavLink>
          </>
        )}
        {user?.role === 'ADMIN' && <NavLink to="/admin">管理</NavLink>}
      </nav>
      {user ? (
        <span className="row">
          <span className="muted">{user.email}</span>
          <button
            className="secondary"
            onClick={() => {
              logout().then(() => navigate('/'))
            }}
          >
            退出
          </button>
        </span>
      ) : (
        <NavLink to="/login">登录 / 注册</NavLink>
      )}
    </header>
  )
}

export default function App() {
  const { user, ready } = useAuth()
  if (!ready) return <div className="container">加载中…</div>
  return (
    <>
      <TopBar />
      <div className="container">
        <Routes>
          <Route path="/" element={<Discovery />} />
          <Route path="/events/:id" element={<EventDetail />} />
          <Route path="/login" element={<Login />} />
          <Route
            path="/checkout/:bookingId"
            element={user ? <Checkout /> : <Navigate to="/login" replace />}
          />
          <Route path="/orders" element={user ? <Orders /> : <Navigate to="/login" replace />} />
          <Route
            path="/bookings/:id"
            element={user ? <BookingDetail /> : <Navigate to="/login" replace />}
          />
          <Route
            path="/organiser"
            element={user ? <Organiser /> : <Navigate to="/login" replace />}
          />
          <Route path="/redeem" element={user ? <Redeem /> : <Navigate to="/login" replace />} />
          <Route path="/admin" element={user ? <Admin /> : <Navigate to="/login" replace />} />
        </Routes>
      </div>
    </>
  )
}
