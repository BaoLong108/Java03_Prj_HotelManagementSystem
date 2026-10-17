import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '../../shared/api/client'
import { clearSession, endSession, readSession, saveSession, SESSION_ENDED_EVENT, tokenExpiresAt } from '../../shared/auth/session'
import { authApi } from './api'
import { isAuthUser } from './types'
import { safeReturnPath } from './navigation'
import type { AuthResponse, AuthUser, LoginRequest, RegisterRequest } from './types'

type Status = 'loading' | 'anonymous' | 'authenticated' | 'unavailable'
type AuthContextValue = {
  user: AuthUser | null; status: Status; sessionExpired: boolean; sessionMessage: string
  login: (body: LoginRequest, signal?: AbortSignal) => Promise<void>
  register: (body: RegisterRequest, signal?: AbortSignal) => Promise<boolean>
  logout: () => void; retry: () => void
}
const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  const [expiresAt, setExpiresAt] = useState<number | null>(null)
  const [sessionExpired, setSessionExpired] = useState(false)
  const [sessionMessage, setSessionMessage] = useState('')
  const [version, setVersion] = useState(0)
  const generation = useRef(0)
  const location = useLocation()
  const currentLocation = useRef(location)
  currentLocation.current = location
  const navigate = useNavigate()

  useEffect(() => {
    function onSessionEnded(event: Event) {
      generation.current++
      setUser(null); setExpiresAt(null); setStatus('anonymous'); setSessionExpired(true)
      setSessionMessage((event as CustomEvent<string>).detail)
      const current = currentLocation.current
      const from = safeReturnPath(current.pathname + current.search + current.hash) ?? safeReturnPath(current.state?.from)
      navigate('/login', { replace: true, state: { from } })
    }
    window.addEventListener(SESSION_ENDED_EVENT, onSessionEnded)
    return () => window.removeEventListener(SESSION_ENDED_EVENT, onSessionEnded)
  }, [navigate])

  useEffect(() => {
    const session = readSession()
    if (!session) { setStatus('anonymous'); return }
    const controller = new AbortController()
    const startedGeneration = generation.current
    setStatus('loading')
    setExpiresAt(session.expiresAt)
    authApi.me(controller.signal).then((currentUser) => {
      if (!controller.signal.aborted && generation.current === startedGeneration
          && readSession()?.accessToken === session.accessToken) {
        if (!isAuthUser(currentUser)) throw new ApiError(502, 'Thông tin tài khoản từ máy chủ không hợp lệ.')
        saveSession({ ...session, user: currentUser })
        setUser(currentUser); setExpiresAt(session.expiresAt); setStatus('authenticated')
      }
    }).catch((error) => {
      if (controller.signal.aborted || generation.current !== startedGeneration) return
      const currentSession = readSession()
      if (currentSession?.accessToken !== session.accessToken) return
      if (error instanceof ApiError && error.status === 401) {
        endSession(error.message)
      } else setStatus('unavailable')
    })
    return () => controller.abort()
  }, [version])

  useEffect(() => {
    if (expiresAt === null) return
    const startedGeneration = generation.current
    const checkExpiry = () => {
      if (generation.current === startedGeneration && Date.now() >= expiresAt) endSession()
    }
    const timer = window.setTimeout(checkExpiry, Math.max(0, expiresAt - Date.now()))
    window.addEventListener('focus', checkExpiry)
    document.addEventListener('visibilitychange', checkExpiry)
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('focus', checkExpiry)
      document.removeEventListener('visibilitychange', checkExpiry)
    }
  }, [expiresAt])

  function acceptSession(response: AuthResponse) {
    const jwtExpiry = typeof response?.accessToken === 'string' ? tokenExpiresAt(response.accessToken) : null
    if (!jwtExpiry || jwtExpiry <= Date.now() || response.tokenType !== 'Bearer'
        || !Number.isFinite(response.expiresIn) || response.expiresIn <= 0 || response.expiresIn > 86400
        || !isAuthUser(response.user)) {
      throw new ApiError(502, 'Phản hồi đăng nhập không hợp lệ. Vui lòng thử lại.', 'INVALID_AUTH_RESPONSE')
    }
    const expiry = Math.min(jwtExpiry, Date.now() + response.expiresIn * 1000)
    saveSession({ accessToken: response.accessToken, expiresAt: expiry, user: response.user })
    generation.current++
    setExpiresAt(expiry); setUser(response.user); setSessionExpired(false); setStatus('authenticated')
    setSessionMessage('')
  }
  const logout = useCallback(() => {
    generation.current++
    clearSession(); setUser(null); setExpiresAt(null); setSessionExpired(false); setStatus('anonymous')
    setSessionMessage('')
  }, [])

  function checkCurrentRequest(startedGeneration: number, signal?: AbortSignal) {
    if (signal?.aborted || startedGeneration !== generation.current) throw new DOMException('Request cancelled', 'AbortError')
  }

  return <AuthContext.Provider value={{ user, status, sessionExpired, sessionMessage,
    login: async (body, signal) => {
      const startedGeneration = generation.current
      const response = await authApi.login(body, signal)
      checkCurrentRequest(startedGeneration, signal)
      acceptSession(response)
    },
    register: async (body, signal) => {
      const startedGeneration = generation.current
      const response = await authApi.register(body, signal)
      checkCurrentRequest(startedGeneration, signal)
      if (response && 'accessToken' in response) { acceptSession(response); return true }
      if (response !== undefined && !isAuthUser(response)) throw new ApiError(502, 'Phản hồi đăng ký không hợp lệ. Vui lòng thử lại.')
      return false
    },
    logout, retry: () => setVersion((value) => value + 1),
  }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('useAuth must be used inside AuthProvider')
  return auth
}
