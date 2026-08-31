import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { api, refreshToken, setAccessToken } from './api'

export interface SessionUser {
  id: string
  email: string
  role: string
  displayName: string | null
}

interface AuthContextValue {
  user: SessionUser | null
  ready: boolean
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string, displayName: string) => Promise<void>
  logout: () => Promise<void>
  refresh: () => Promise<boolean>
}

const AuthContext = createContext<AuthContextValue>(null!)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null)
  const [ready, setReady] = useState(false)

  // Session restore: the HttpOnly refresh cookie rotates into a fresh access token.
  const refresh = useCallback(async () => {
    try {
      const data = await refreshToken()
      setAccessToken(data.accessToken)
      setUser(data.user)
      return true
    } catch {
      setAccessToken(null)
      setUser(null)
      return false
    }
  }, [])

  useEffect(() => {
    refresh().finally(() => setReady(true))
  }, [refresh])

  const login = useCallback(async (email: string, password: string) => {
    const data = await api<{ accessToken: string; user: SessionUser }>('POST', '/api/v1/auth/login', {
      email,
      password,
    })
    setAccessToken(data.accessToken)
    setUser(data.user)
  }, [])

  const register = useCallback(async (email: string, password: string, displayName: string) => {
    const data = await api<{ accessToken: string; user: SessionUser }>('POST', '/api/v1/auth/register', {
      email,
      password,
      displayName,
    })
    setAccessToken(data.accessToken)
    setUser(data.user)
  }, [])

  const logout = useCallback(async () => {
    await api('POST', '/api/v1/auth/logout', {}).catch(() => undefined)
    setAccessToken(null)
    setUser(null)
  }, [])

  const value = useMemo(
    () => ({ user, ready, login, register, logout, refresh }),
    [user, ready, login, register, logout, refresh],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}
