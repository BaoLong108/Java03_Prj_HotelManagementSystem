import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '../../shared/api/client'
import { clearSession, endSession, readSession, saveSession, SESSION_ENDED_EVENT } from '../../shared/auth/session'
import { authApi } from './api'
import type { AuthResponse, AuthUser, LoginRequest, RegisterRequest } from './types'

type Status = 'loading' | 'anonymous' | 'authenticated' | 'unavailable'
type AuthContextValue = {
  user: AuthUser | null; status: Status; sessionExpired: boolean
  login: (body: LoginRequest) => Promise<void>
  register: (body: RegisterRequest) => Promise<void>
  logout: () => void; retry: () => void
}
const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  const [expiresAt, setExpiresAt] = useState<number | null>(null)
  const [sessionExpired, setSessionExpired] = useState(false)
  const [version, setVersion] = useState(0)

  useEffect(() => {
    function onSessionEnded() {
      setUser(null); setExpiresAt(null); setStatus('anonymous'); setSessionExpired(true)
    }
    window.addEventListener(SESSION_ENDED_EVENT, onSessionEnded)
    return () => window.removeEventListener(SESSION_ENDED_EVENT, onSessionEnded)
  }, [])

  useEffect(() => {
    const session = readSession()
    if (!session) { setStatus('anonymous'); return }
    const controller = new AbortController()
    setStatus('loading')
    authApi.me(controller.signal).then((currentUser) => {
      if (!controller.signal.aborted && readSession()?.accessToken === session.accessToken) {
        setUser(currentUser); setExpiresAt(session.expiresAt); setStatus('authenticated')
      }
    }).catch((error) => {
      if (controller.signal.aborted) return
      const currentSession = readSession()
      if (currentSession && currentSession.accessToken !== session.accessToken) return
      if (error instanceof ApiError && error.status === 401) {
        clearSession(); setStatus('anonymous'); setSessionExpired(true)
      } else setStatus('unavailable')
    })
    return () => controller.abort()
  }, [version])

  useEffect(() => {
    if (expiresAt === null) return
    const timer = window.setTimeout(endSession, Math.max(0, expiresAt - Date.now()))
    return () => window.clearTimeout(timer)
  }, [expiresAt])

  function acceptSession(response: AuthResponse) {
    const expiry = Date.now() + response.expiresIn * 1000
    saveSession({ accessToken: response.accessToken, expiresAt: expiry })
    setExpiresAt(expiry); setUser(response.user); setSessionExpired(false); setStatus('authenticated')
  }
  const logout = useCallback(() => {
    clearSession(); setUser(null); setExpiresAt(null); setSessionExpired(false); setStatus('anonymous')
  }, [])

  return <AuthContext.Provider value={{ user, status, sessionExpired,
    login: async (body) => acceptSession(await authApi.login(body)),
    register: async (body) => acceptSession(await authApi.register(body)),
    logout, retry: () => setVersion((value) => value + 1),
  }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('useAuth must be used inside AuthProvider')
  return auth
}
