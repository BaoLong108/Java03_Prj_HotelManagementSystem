export type Role = 'CUSTOMER' | 'STAFF' | 'ADMIN'
export type AuthUser = { id: number; email: string; fullName: string; roles: Role[] }
export type AuthResponse = { accessToken: string; tokenType: 'Bearer'; expiresIn: number; user: AuthUser }
export type LoginRequest = { email: string; password: string }
export type RegisterRequest = LoginRequest & { fullName: string; phoneNumber?: string }
export const roleLabels: Record<Role, string> = {
  CUSTOMER: 'Khách hàng', STAFF: 'Nhân viên', ADMIN: 'Quản trị viên',
}
