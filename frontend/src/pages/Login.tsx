import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'
import { ApiError } from '../api'

export default function Login() {
  const { login, register } = useAuth()
  const navigate = useNavigate()
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => setError(''), [mode])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      if (mode === 'login') {
        await login(email, password)
      } else {
        await register(email, password, displayName)
      }
      navigate('/')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '网络错误，请稍后重试')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-page card">
      <h2>{mode === 'login' ? '登录' : '注册普通用户'}</h2>
      <form onSubmit={submit}>
        <label>邮箱</label>
        <input value={email} onChange={(e) => setEmail(e.target.value)} type="email" required />
        <label>密码{mode === 'register' && '（至少 10 位）'}</label>
        <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" required />
        {mode === 'register' && (
          <>
            <label>昵称</label>
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </>
        )}
        {error && <p className="error-text">{error}</p>}
        <button type="submit" disabled={busy} style={{ width: '100%' }}>
          {busy ? '提交中…' : mode === 'login' ? '登录' : '注册'}
        </button>
      </form>
      <p className="muted">
        {mode === 'login' ? '还没有账号？' : '已有账号？'}
        <a href="#" onClick={(e) => { e.preventDefault(); setMode(mode === 'login' ? 'register' : 'login') }}>
          {mode === 'login' ? '去注册' : '去登录'}
        </a>
      </p>
      <p className="muted" style={{ fontSize: 12 }}>
        演示账号（seed 数据）：user@eventpulse.dev / User!234567890，
        organiser@eventpulse.dev / Organiser!234567890，admin@eventpulse.dev / Admin!234567890
      </p>
    </div>
  )
}
