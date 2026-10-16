// Offset-bearing timestamps only; never interpret a backend expiry as browser local time.
export function holdSecondsRemaining(holdExpiresAt: string, now = Date.now()): number | null {
  if (!/(Z|[+-]\d{2}:\d{2})$/i.test(holdExpiresAt)) return null
  const expiry = Date.parse(holdExpiresAt)
  return Number.isFinite(expiry) && Number.isFinite(now) ? Math.max(0, Math.ceil((expiry - now) / 1000)) : null
}
export function formatHoldTime(seconds: number): string {
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}
