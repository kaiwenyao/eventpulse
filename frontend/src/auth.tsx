import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { ApiError, TOKEN_KEY, api, getAccessToken, setAccessToken } from './api'
import { AI_CONVERSATION_KEY } from './lib/aiConversation'

export interface SessionUser {
  id: number
  email: string
  name: string
  role: string
}

interface AuthContextValue {
  user: SessionUser | null
  ready: boolean
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string, name: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue>(null!)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null)
  const [ready, setReady] = useState(false)

  // Shared session probe. A /me response may only be applied while this tab
  // still holds the token it was requested with, and only a definitive 401/403
  // for a token we still hold kills the session — a transport error or a race
  // with another tab's newer login must never wipe (and broadcast away) a
  // perfectly good token.
  const verifySession = useCallback(
    (token: string) =>
      api<SessionUser>('GET', '/api/auth/me')
        .then((me) => {
          if (getAccessToken() === token) setUser(me)
        })
        .catch((err: unknown) => {
          if (getAccessToken() !== token) return
          if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
            setAccessToken(null)
            setUser(null)
          }
        }),
    [],
  )

  useEffect(() => {
    const token = getAccessToken()
    if (!token) {
      // Token restore is an external session check; ready must flip after mount.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setReady(true)
      return
    }
    verifySession(token).finally(() => setReady(true))
  }, [verifySession])

  // The session can change in another tab (login, logout, account switch);
  // the storage event is the only signal this tab gets. Keep the React-level
  // user in sync so the top bar and route guards never serve a stale identity.
  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key !== TOKEN_KEY) return
      if (!e.newValue) {
        setUser(null)
        return
      }
      void verifySession(e.newValue)
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [verifySession])

  const login = useCallback(async (email: string, password: string) => {
    const data = await api<{ token: string; user: SessionUser }>('POST', '/api/auth/login', { email, password })
    setAccessToken(data.token)
    setUser(data.user)
  }, [])

  const register = useCallback(async (email: string, password: string, name: string) => {
    const data = await api<{ token: string; user: SessionUser }>('POST', '/api/auth/register', {
      email,
      password,
      name,
    })
    setAccessToken(data.token)
    setUser(data.user)
  }, [])

  const logout = useCallback(() => {
    setAccessToken(null)
    setUser(null)
    // AI 会话 id 是按账号的：留着的话，换个账号登录就会带着别人的 id 去请求
    // （服务端归属校验会 403，但不该让它发生）。
    try {
      localStorage.removeItem(AI_CONVERSATION_KEY)
    } catch {
      // localStorage 不可用时无事可做。
    }
  }, [])

  const value = useMemo(() => ({ user, ready, login, register, logout }), [user, ready, login, register, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}
