import { apiRequest } from '../../shared/api/client'
import type { AuthResponse, AuthUser, LoginRequest, RegisterRequest } from './types'

export const authApi = {
  login: (body: LoginRequest) => apiRequest<AuthResponse>('/api/v1/auth/login', { method: 'POST', body, auth: false }),
  register: (body: RegisterRequest) => apiRequest<AuthResponse>('/api/v1/auth/register', { method: 'POST', body, auth: false }),
  me: (signal?: AbortSignal) => apiRequest<AuthUser>('/api/v1/auth/me', { signal }),
}
