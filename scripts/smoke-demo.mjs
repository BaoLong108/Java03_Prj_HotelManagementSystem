// Run against an isolated test/demo database. Creates one account and one paid demo booking.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

const base = (process.env.DEMO_BASE_URL ?? 'http://localhost:5173').replace(/\/$/, '')
async function request(path, { token, body, status = 200 } = {}) {
  const response = await fetch(base + path, {
    method: body === undefined ? 'GET' : 'POST', signal: AbortSignal.timeout(15000),
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  assert.equal(response.status, status, `${path}: unexpected HTTP ${response.status}`)
  return response
}

assert.match(await (await request('/login')).text(), /<html/)
assert.equal((await (await request('/actuator/health')).json()).status, 'UP')
const email = `smoke-${randomUUID()}@hotel-pms.test`
await request('/api/v1/auth/register', { body: { email, fullName: 'Compose Smoke Customer', password: 'SmokeDemo123!', phoneNumber: '0901234567' }, status: 201 })
const session = await (await request('/api/v1/auth/login', { body: { email, password: 'SmokeDemo123!' } })).json()
const token = session.accessToken
assert.ok(token)
const start = new Date()
start.setUTCDate(start.getUTCDate() + 60)
start.setUTCHours(5, 0, 0, 0)
const end = new Date(start)
end.setUTCDate(end.getUTCDate() + 1)
end.setUTCHours(3)
const stay = { startAt: start.toISOString(), endAt: end.toISOString(), guestCount: 2 }
const query = new URLSearchParams(Object.entries(stay).map(([key, value]) => [key, String(value)]))
const rooms = await (await request(`/api/v1/rooms/availability?${query}`)).json()
assert.ok(rooms.length, 'Demo seed must have an available room type')
const roomTypeId = rooms[0].id
query.set('roomTypeId', String(roomTypeId))
const quote = await (await request(`/api/v1/pricing/quote?${query}`, { token })).json()
const booking = await (await request('/api/v1/bookings', { token, status: 201, body: { ...stay, roomTypeId } })).json()
assert.equal(booking.status, 'PENDING_PAYMENT')
assert.equal(booking.payableAmount, quote.payableAmount)
const paymentBody = { bookingCode: booking.bookingCode, idempotencyKey: `smoke-${randomUUID()}` }
const paid = await (await request('/api/v1/payments/demo', { token, body: paymentBody })).json()
const repeated = await (await request('/api/v1/payments/demo', { token, body: paymentBody })).json()
assert.deepEqual(repeated, paid, 'Repeated payment must return the same result')
const confirmed = await (await request(`/api/v1/bookings/${booking.bookingCode}`, { token })).json()
assert.equal(confirmed.status, 'CONFIRMED')
assert.equal(confirmed.payableAmount, booking.payableAmount)
const myBookings = await (await request('/api/v1/bookings', { token })).json()
assert.ok(myBookings.some(item => item.bookingCode === booking.bookingCode))
const membership = await (await request('/api/v1/customers/me/membership', { token })).json()
assert.equal(membership.pointsBalance, 0, 'A future confirmed stay must not earn points early')
assert.equal(membership.tierCode, 'STANDARD')
const history = await (await request('/api/v1/customers/me/loyalty-awards', { token })).json()
assert.equal(history.totalElements, 0)
await request('/api/v1/customers/me/membership', { status: 401 })
await request('/api/v1/staff/room-calendar?from=2032-01-01&to=2032-01-08', { token, status: 403 })
const pdf = await request(`/api/v1/bookings/${booking.bookingCode}/confirmation.pdf`, { token })
assert.equal(Buffer.from(await pdf.arrayBuffer()).subarray(0, 5).toString(), '%PDF-')
console.log(`PASS: SPA, health, register/login, availability/quote, hold, demo payment/replay, own bookings, membership RBAC, PDF (${booking.bookingCode})`)
