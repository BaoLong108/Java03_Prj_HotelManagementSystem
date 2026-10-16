export type Role = 'CUSTOMER' | 'STAFF' | 'ADMIN'
export type SessionUser = { email: string; fullName: string; roles: Role[] }
export type AuthUser = SessionUser & { id: number }
export type AuthResponse = { accessToken: string; tokenType: 'Bearer'; expiresIn: number; user: AuthUser }
// Current backend returns AuthResponse; also support successful registration without a token.
export type RegisterResponse = AuthResponse | AuthUser | void
export type LoginRequest = { email: string; password: string }
export type RegisterRequest = LoginRequest & { fullName: string; phoneNumber?: string }
export const roleLabels: Record<Role, string> = {
  CUSTOMER: 'Khách hàng', STAFF: 'Nhân viên', ADMIN: 'Quản trị viên',
}

export function isSessionUser(value: unknown): value is SessionUser {
  if (!value || typeof value !== 'object') return false
  const user = value as SessionUser
  return typeof user.fullName === 'string' && Boolean(user.fullName.trim())
    && typeof user.email === 'string' && Boolean(user.email.trim())
    && Array.isArray(user.roles) && user.roles.length > 0
    && user.roles.every((role) => role === 'CUSTOMER' || role === 'STAFF' || role === 'ADMIN')
}

export function isAuthUser(value: unknown): value is AuthUser {
  return isSessionUser(value) && 'id' in value && Number.isSafeInteger(value.id) && Number(value.id) > 0
}
