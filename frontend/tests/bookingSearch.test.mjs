import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'
import { build } from 'vite'

const modulesDirectory = resolve('node_modules')
const output = await mkdtemp(resolve(modulesDirectory, '.m5-03-tests-'))
const originalFetch = globalThis.fetch
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage')
after(async () => {
  globalThis.fetch = originalFetch
  if (originalStorage) Object.defineProperty(globalThis, 'sessionStorage', originalStorage)
  else delete globalThis.sessionStorage
  if (!resolve(output).startsWith(modulesDirectory + sep)) throw new Error('Unexpected test directory')
  await rm(output, { recursive: true, force: true })
})
await build({ configFile: false, logLevel: 'silent', define: { 'import.meta.env.VITE_USE_FIXTURES': '"false"' },
  build: { ssr: 'tests/bookingSearch.entry.ts', outDir: output, rollupOptions: { output: { entryFileNames: 'entry.mjs' } } } })
const api = await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const real = { id: 15, bookingCode: 'BK-REAL-001', customerId: 1, roomTypeId: 2, guestCount: 2,
  startAt: '2026-10-08T05:00:00Z', endAt: '2026-10-09T03:00:00Z', status: 'PENDING_PAYMENT',
  holdExpiresAt: '2026-10-08T04:45:00Z', baseAmount: 1200000, discountRate: 0.1, payableAmount: 1080000 }
const fixture = () => api.bookingSearchFixture({ bookingCode: 'BK-MAU-001' })

test('search validates one nonempty code or phone and never permits a full listing', async () => {
  assert.throws(() => api.bookingQuery('bookingCode', '  '), /Nhập mã/)
  assert.throws(() => api.bookingQuery('phone', ''), /Nhập số/)
  assert.throws(() => api.bookingQuery('bookingCode', '../../private'), /Mã booking/)
  assert.throws(() => api.bookingQuery('phone', 'a0900000123'), /7–15/)
  assert.deepEqual(api.bookingQuery('bookingCode', ' BK-001 '), { bookingCode: 'BK-001' })
  assert.deepEqual(api.bookingQuery('phone', '+84 (900) 000-123'), { phone: '+84900000123' })
  let called = false
  globalThis.fetch = async () => { called = true; throw new Error('Must not call') }
  for (const query of [{}, { bookingCode: '', phone: '0900000123' }, { bookingCode: '' }, { phone: undefined }]) await assert.rejects(api.searchBookings(query))
  assert.equal(called, false)
})
test('real BookingResponse maps id and numeric snapshots, preserves backend status, omits identity and missing metadata', () => {
  const item = api.parseBookingDetail(real)
  assert.equal(item.bookingId, 15)
  assert.equal(item.status, 'PENDING_PAYMENT')
  assert.equal(item.payableAmount, '1080000')
  assert.equal(item.discountRate, '0.1')
  for (const key of ['customerId', 'customer', 'roomTypeName', 'roomNumber']) assert.equal(key in item, false)
  assert.throws(() => api.parseBookingDetail({ ...real, payableAmount: '1080000' }), /không hợp lệ/)
  assert.throws(() => api.parseBookingDetail({ ...real, endAt: real.startAt }), /không hợp lệ/)
  assert.throws(() => api.parseBookingDetail({ ...real, status: 'UNKNOWN' }), /không hợp lệ/)
})
test('proposed lookup retains masked contacts only, never full name or raw PII', () => {
  const data = fixture()
  const item = api.parseBookingSearch(data).items[0]
  assert.deepEqual(item.customer, { phoneMasked: '090****123', emailMasked: 'k***@example.test' })
  assert.equal(JSON.stringify(item).includes('Khách mẫu'), false)
  for (const patch of [{ phoneMasked: '0900000123' }, { emailMasked: 'khach@example.test' }, { phone: '0900000123' }]) {
    const bad = fixture(); Object.assign(bad.items[0].customer, patch)
    assert.throws(() => api.parseBookingSearch(bad), error => error.code === 'UNMASKED_CUSTOMER_DATA')
  }
  assert.throws(() => api.parseBookingDetail({ ...real, phone: '0900000123' }), error => error.code === 'UNMASKED_CUSTOMER_DATA')
})
test('proposed decimal strings and nullable room are validated separately from real numbers', () => {
  const data = fixture(); data.items[0].roomNumber = null
  assert.equal(api.parseBookingSearch(data).items[0].roomNumber, null)
  data.items[0].payableAmount = 1080000
  assert.throws(() => api.parseBookingSearch(data), /không hợp lệ/)
  const duplicate = fixture(); duplicate.items.push(duplicate.items[0])
  assert.throws(() => api.parseBookingSearch(duplicate), /không hợp lệ/)
})
test('VND presentation does not recompute prices or lose decimal precision', () => {
  assert.equal(api.formatVnd('1200000.00'), '1.200.000 ₫')
  assert.equal(api.formatVnd('999999999999999.99'), '999.999.999.999.999,99 ₫')
  assert.equal(api.formatVnd('100.50'), '100,5 ₫')
  assert.equal(api.formatVnd('0.00'), '0 ₫')
  assert.equal(api.formatVnd('Infinity'), 'Chưa có số tiền')
  const item = api.parseBookingDetail({ ...real, baseAmount: 999, discountRate: 0.2, payableAmount: 123.45 })
  assert.equal(api.formatVnd(item.payableAmount), '123,45 ₫')
})
test('calendar deep link uses the hotel date across UTC midnight, without contact information', () => {
  assert.equal(api.bookingCalendarLink({ ...api.parseBookingDetail(real), startAt: '2026-10-07T18:00:00Z' }), '/staff/calendar?from=2026-10-08&roomTypeId=2')
  assert.equal(api.bookingCalendarLink(api.parseBookingSearch(fixture()).items[0]), '/staff/calendar?from=2026-10-08')
})
test('fixture lookup is exact, includes all five statuses and shares dates/codes with calendar', () => {
  assert.equal(api.bookingSearchFixture({ bookingCode: 'BK-MAU-' }).items.length, 0)
  assert.equal(api.bookingSearchFixture({ phone: '0900000999' }).items.length, 0)
  const items = api.parseBookingSearch(api.bookingSearchFixture({ phone: '0900000123' })).items
  assert.equal(items.length, 6)
  assert.equal(new Set(items.map(item => item.status)).size, 5)
  assert.equal(items.find(item => item.bookingCode === 'BK-MAU-005').status, 'COMPLETED')
})
test('fixtures off uses real code endpoint and proposed phone endpoint with session token and exactly one parameter', async () => {
  const token = `header.${Buffer.from(JSON.stringify({ exp: Math.floor(Date.now()/1000) + 3600 })).toString('base64url')}.signature`
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: { getItem: () => JSON.stringify({ accessToken: token, expiresAt: Date.now() + 3600000 }) } })
  const calls = []
  globalThis.fetch = async (url, options) => {
    assert.equal(options.headers.Authorization, `Bearer ${token}`)
    calls.push(url)
    return Response.json(url.includes('/staff/') ? fixture() : real)
  }
  assert.equal((await api.searchBookings({ bookingCode: real.bookingCode })).items[0].bookingId, 15)
  await api.searchBookings({ phone: '+84900000123' })
  assert.deepEqual(calls, ['/api/v1/bookings/BK-REAL-001', '/api/v1/staff/bookings/search?phone=%2B84900000123'])
})
test('only BOOKING_NOT_FOUND is empty; missing API, authorization, malformed and server errors never fall back to fixture', async () => {
  globalThis.fetch = async () => Response.json({ code: 'BOOKING_NOT_FOUND', message: 'Không tìm thấy' }, { status: 404 })
  assert.deepEqual(await api.searchBookings({ bookingCode: 'MISSING' }), { items: [] })
  for (const [status, code] of [[404, 'NOT_FOUND'], [403, 'FORBIDDEN'], [500, 'INTERNAL_ERROR']]) {
    globalThis.fetch = async () => Response.json({ code, message: 'Lỗi máy chủ kiểm thử' }, { status })
    for (const query of [{ bookingCode: 'BK-MAU-001' }, { phone: '0900000123' }]) {
      await assert.rejects(api.searchBookings(query), error => error.status === status && error.message === 'Lỗi máy chủ kiểm thử')
    }
  }
  globalThis.fetch = async () => Response.json({ ...real, bookingCode: 'WRONG-CODE' })
  await assert.rejects(api.searchBookings({ bookingCode: real.bookingCode }), /không hợp lệ/)
})
