import { useEffect, useState } from 'react'
import { apiRequest } from './client'

export function useServiceHealth() {
  const [status, setStatus] = useState<'loading' | 'online' | 'offline'>('loading')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setStatus('loading')
    apiRequest<{ status: string }>('/actuator/health', { auth: false, signal: controller.signal })
      .then((health) => { if (!controller.signal.aborted) setStatus(health.status === 'UP' ? 'online' : 'offline') })
      .catch(() => { if (!controller.signal.aborted) setStatus('offline') })
    return () => controller.abort()
  }, [attempt])
  return { status, retry: () => setAttempt((value) => value + 1) }
}
