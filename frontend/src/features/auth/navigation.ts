import type { Role } from './types'

export function safeReturnPath(value: unknown): string | undefined {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return undefined
  // Reject encoded separators, backslashes and control characters; never redirect off-site.
  if (/[\\\u0000-\u0020]|%2f|%5c|%0[ad]/i.test(value)) return undefined
  const url = new URL(value, 'https://hotel-pms.invalid')
  if (url.origin !== 'https://hotel-pms.invalid' || /^\/(login|register|403)(\/|$)/i.test(url.pathname)) return undefined
  return url.pathname + url.search + url.hash
}

export function loginDestination(roles: readonly Role[], from: unknown): string {
  const requested = safeReturnPath(from)
  const isStaff = roles.some((role) => role === 'STAFF' || role === 'ADMIN')
  if (requested) {
    const pathname = requested.split(/[?#]/)[0]
    if (/^\/admin(\/|$)/i.test(pathname) && !roles.includes('ADMIN')) return '/403'
    if (/^\/staff(\/|$)/i.test(pathname) && !isStaff) return '/403'
    // Operational accounts land in their workspace, or resume an allowed staff/admin page.
    if (!isStaff || /^\/(staff|admin)(\/|$)/i.test(pathname)) return requested
  }
  return isStaff ? '/staff/calendar' : '/'
}
