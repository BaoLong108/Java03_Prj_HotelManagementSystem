import { isSessionUser } from '../../features/auth/types'
import type { SessionUser } from '../../features/auth/types'

const SESSION_KEY = 'hotel-pms.session.v1'
export const SESSION_ENDED_EVENT = 'hotel-pms:session-ended'
export type StoredSession = { accessToken: string; expiresAt: number; user?: SessionUser }

// Only used to schedule expiry. Signature/account/roles are verified by /auth/me, never by decoding JWT.
export function tokenExpiresAt(token: string): number | null {
  try {
    if (!/^[\w-]+\.[\w-]+\.[\w-]+$/.test(token)) return null
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    const expiry = payload.exp * 1000
    return typeof payload.exp === 'number' && Number.isSafeInteger(expiry) && expiry > 0 ? expiry : null
  } catch { return null }
}

// Cached display data is never used to authorize routes. Old token-only sessions remain readable.
export function readSession(): StoredSession | null {
  let raw: string | null
  try { raw = sessionStorage.getItem(SESSION_KEY) } catch { return null }
  if (raw === null) return null
  try {
    const value = JSON.parse(raw)
    const tokenExpiry = typeof value?.accessToken === 'string' ? tokenExpiresAt(value.accessToken) : null
    if (tokenExpiry && tokenExpiry > Date.now()
        && typeof value.expiresAt === 'number' && Number.isFinite(value.expiresAt)
        && value.expiresAt > Date.now()) return {
      accessToken: value.accessToken, expiresAt: Math.min(value.expiresAt, tokenExpiry),
      ...(isSessionUser(value.user) ? { user: displayUser(value.user) } : {}),
    }
  } catch { /* Malformed session: expire it just like an invalid token. */ }
  endSession()
  return null
}

function displayUser(user: SessionUser): SessionUser {
  return { fullName: user.fullName, email: user.email, roles: [...user.roles] }
}

export function saveSession(session: StoredSession) {
  try { sessionStorage.setItem(SESSION_KEY, JSON.stringify({
    accessToken: session.accessToken, expiresAt: session.expiresAt,
    ...(session.user ? { user: displayUser(session.user) } : {}),
  })) }
  catch { throw new Error('Trình duyệt đang chặn lưu phiên. Hãy cho phép lưu dữ liệu cho trang này.') }
}

export function clearSession() {
  try { sessionStorage.removeItem(SESSION_KEY) } catch { /* Already unavailable. */ }
}

export function endSession(message = 'Phiên đăng nhập đã hết hạn hoặc không còn hợp lệ. Vui lòng đăng nhập lại.') {
  clearSession()
  window.dispatchEvent(new CustomEvent(SESSION_ENDED_EVENT, { detail: message }))
}
