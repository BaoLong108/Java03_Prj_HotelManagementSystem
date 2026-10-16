import { apiRequest } from '../../shared/api/client'
import type { AuthResponse, AuthUser, LoginRequest, RegisterRequest, RegisterResponse } from './types'

// Verified against M1-03 AuthController/DTOs and docs/auth.md (dcf4e8c).
export const authApi = {
  login: (body: LoginRequest, signal?: AbortSignal) => apiRequest<AuthResponse>('/api/v1/auth/login', { method: 'POST', body, auth: false, signal }),
  register: (body: RegisterRequest, signal?: AbortSignal) => apiRequest<RegisterResponse>('/api/v1/auth/register', { method: 'POST', body, auth: false, signal }),
  me: (signal?: AbortSignal) => apiRequest<AuthUser>('/api/v1/auth/me', { signal }),
}
