const SESSION_KEY = 'hotel-pms.session.v1'
export const SESSION_ENDED_EVENT = 'hotel-pms:session-ended'
export type StoredSession = { accessToken: string; expiresAt: number }

// Keep this tab's token only. Passwords and cached roles are never persisted.
export function readSession(): StoredSession | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(SESSION_KEY) ?? 'null')
    if (value && typeof value.accessToken === 'string' && value.accessToken.length > 0
        && typeof value.expiresAt === 'number' && Number.isFinite(value.expiresAt)
        && value.expiresAt > Date.now()) return value
    sessionStorage.removeItem(SESSION_KEY)
  } catch { /* Storage may be disabled or contain an outdated value. */ }
  return null
}

export function saveSession(session: StoredSession) {
  try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(session)) }
  catch { throw new Error('Trình duyệt đang chặn lưu phiên. Hãy cho phép lưu dữ liệu cho trang này.') }
}

export function clearSession() {
  try { sessionStorage.removeItem(SESSION_KEY) } catch { /* Already unavailable. */ }
}

export function endSession() {
  clearSession()
  window.dispatchEvent(new Event(SESSION_ENDED_EVENT))
}
