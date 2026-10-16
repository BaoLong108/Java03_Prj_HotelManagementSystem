import { endSession, readSession } from '../auth/session'

const baseUrl = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly details: Record<string, string>

  constructor(status: number, message: string, code?: string, details: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}

type ApiErrorBody = { code?: string; message?: string; details?: Record<string, string> }
type RequestOptions = { method?: string; body?: unknown; token?: string; auth?: boolean; signal?: AbortSignal }

export async function apiRequest<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const token = options.auth === false ? undefined : options.token ?? readSession()?.accessToken
  let response: Response
  try {
    response = await fetch(`${baseUrl}${path}`, {
      method: options.method ?? 'GET',
      credentials: 'omit',
      headers: {
        ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (error) {
    if (options.signal?.aborted) throw error
    throw new ApiError(0, 'Chưa kết nối được máy chủ. Vui lòng thử lại sau.', 'NETWORK_ERROR')
  }

  if (!response.ok) {
    const error = await response.json().catch(() => null) as ApiErrorBody | null
    const message = typeof error?.message === 'string' && error.message ? error.message : `API trả về HTTP ${response.status}`
    const details = error?.details && typeof error.details === 'object'
      ? Object.fromEntries(Object.entries(error.details).filter(([, value]) => typeof value === 'string')) : {}
    // A stale request must not clear a newer session created while it was in flight.
    // Login/register 401s are form errors, not session-expiry redirects.
    if (response.status === 401 && options.auth !== false) {
      const currentToken = readSession()?.accessToken
      if (token === currentToken) endSession(message)
    }
    throw new ApiError(
      response.status,
      message,
      typeof error?.code === 'string' ? error.code : undefined,
      details,
    )
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Có lỗi xử lý. Vui lòng thử lại.'
}
