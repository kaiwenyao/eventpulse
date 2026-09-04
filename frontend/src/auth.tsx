import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { TOKEN_KEY, api, getAccessToken, setAccessToken } from './api'

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

  useEffect(() => {
    if (!getAccessToken()) {
      // Token restore is an external session check; ready must flip after mount.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setReady(true)
      return
    }
    api<SessionUser>('GET', '/api/auth/me')
      .then((me) => {
        // Another tab may have logged out while the request was in flight;
        // a response for a token we no longer hold must not resurrect the user.
        if (getAccessToken()) setUser(me)
      })
      .catch(() => setAccessToken(null))
      .finally(() => setReady(true))
  }, [])

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
      api<SessionUser>('GET', '/api/auth/me')
        .then((me) => {
          // Out-of-order guard: if the token changed again mid-flight, this
          // response is stale and the newer event's fetch will win.
          if (getAccessToken() === e.newValue) setUser(me)
        })
        .catch(() => {
          setAccessToken(null)
          setUser(null)
        })
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])

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
  }, [])

  const value = useMemo(() => ({ user, ready, login, register, logout }), [user, ready, login, register, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}
