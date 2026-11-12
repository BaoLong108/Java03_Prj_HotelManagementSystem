import assert from 'node:assert/strict'
import { test, after } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'
import { build } from 'vite'
const modules = resolve('node_modules'), output = await mkdtemp(resolve(modules, '.operations-'))
after(async () => { if (!output.startsWith(modules + sep)) throw new Error('Invalid directory'); await rm(output, { recursive: true, force: true }) })
await build({ configFile: false, logLevel: 'silent', build: { ssr: 'tests/operations.entry.ts', outDir: output, rollupOptions: { output: { entryFileNames: 'entry.mjs' } } } })
const api = await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const valid = { physicalRoomId: '1', start: '2030-01-01T12:00', end: '2030-01-02T10:00', reason: 'Bảo trì' }

test('report periods use hotel dates, exclusive end, leap days and a maximum of 92 days', () => {
  assert.deepEqual(api.presetPeriod('7', '2032-03-01'), { from: '2032-02-24', to: '2032-03-02' })
  assert.deepEqual(api.presetPeriod('month', '2032-03-01'), { from: '2032-03-01', to: '2032-03-02' })
  assert.equal(api.reportQuery({ from: '2032-01-01', to: '2032-04-02' }).get('to'), '2032-04-02')
  for (const to of ['2032-01-01', '2031-12-31', '2032-02-30', '2032-04-03']) assert.throws(() => api.reportQuery({ from: '2032-01-01', to }))
  assert.throws(() => api.getHistory({ from: '2032-01-01', to: '2032-01-02' }, '-1'))
})

test('CSV uses authenticated blob request and preserves BOM, decimals and Unicode bytes', async () => {
  const originalFetch = globalThis.fetch, originalStorage = globalThis.sessionStorage
  const exp = Math.floor(Date.now() / 1000) + 3600
  const token = 'e30.' + Buffer.from(JSON.stringify({ exp })).toString('base64url') + '.c2ln'
  globalThis.sessionStorage = { getItem: () => JSON.stringify({ accessToken: token, expiresAt: exp * 1000 }) }
  const source = new Blob(['\uFEFF"Mã","Số tiền"\r\n"Đặt phòng","123456789012345.67"\r\n'], { type: 'text/csv;charset=UTF-8' })
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/v1/admin/reports/bookings.csv?from=2032-01-01&to=2032-01-02')
    assert.equal(options.headers.Authorization, 'Bearer ' + token)
    return new Response(source)
  }
  try { const blob = await api.getCsv({ from: '2032-01-01', to: '2032-01-02' }); assert.deepEqual(new Uint8Array(await blob.arrayBuffer()), new Uint8Array(await source.arrayBuffer())) }
  finally { globalThis.fetch = originalFetch; globalThis.sessionStorage = originalStorage }
})

test('room legacy error field preserves overlap code for the block form', async () => {
  const original = globalThis.fetch
  globalThis.fetch = async () => new Response(JSON.stringify({ error: 'BOOKING_OVERLAP', message: 'Phòng đã có booking hoặc hold còn hạn' }), { status: 409 })
  try {
    await assert.rejects(api.apiRequest('/api/v1/admin/room-blocks', { auth: false }), error => {
      assert.equal(error.code, 'BOOKING_OVERLAP')
      assert.equal(api.blockError(error), 'Phòng đã có booking hoặc giữ chỗ trong khoảng này')
      return true
    })
  } finally { globalThis.fetch = original }
})
test('block validates dates, actual future boundary, room and nonblank reason in hotel time', () => {
  const now = Date.parse('2029-12-31T17:00:00Z')
  assert.deepEqual(api.validateBlock(valid, now), {})
  assert.equal(api.localInstant(valid.start), '2030-01-01T12:00:00+07:00')
  assert.ok(api.validateBlock({ ...valid, start: '2026-02-30T12:00' }, now).startAt)
  assert.ok(api.validateBlock({ ...valid, end: valid.start, reason: ' ', physicalRoomId: '0' }, now).endAt)
  assert.ok(api.validateBlock(valid, Date.parse('2030-01-01T05:00:00Z')).startAt)
  assert.ok(api.validateBlock({ ...valid, reason: ' ' }, now).reason)
})
test('block maps server conflicts without guessing availability', () => {
  assert.match(api.blockError(new api.ApiError(409, 'server', 'BOOKING_OVERLAP')), /booking hoặc giữ chỗ/)
  assert.match(api.blockError(new api.ApiError(409, 'server', 'BLOCK_OVERLAP')), /đã bị chặn/)
  assert.match(api.blockError(new api.ApiError(409, 'server', 'BLOCK_ALREADY_RELEASED')), /đã được mở/)
})
test('daily URL round trips filters and rejects invalid dates, statuses and ids', () => {
  const filters = { date: '2030-01-01', roomTypeId: '2', status: 'CONFIRMED' }
  assert.deepEqual(api.dailyFilters(api.dailyQuery(filters)), filters)
  for (const changed of [{ date: '2030-02-30' }, { status: 'UNKNOWN' }, { roomTypeId: '-1' }]) assert.throws(() => api.dailyQuery({ ...filters, ...changed }))
})
test('daily view model whitelists fields and rejects unmasked contacts', () => {
  const row = { bookingCode: 'BK-1', status: 'CONFIRMED', roomTypeId: 1, roomTypeName: 'Suite', roomNumber: '301', startAt: '2030-01-01T05:00:00Z', endAt: '2030-01-02T03:00:00Z', holdExpiresAt: '2029-01-01T00:00:00Z', guestCount: 2, payableAmount: '100000.00', customer: { phoneMasked: '*******502', emailMasked: 'b***@example.test', fullName: 'Private' }, customerId: 9 }
  const parsed = api.parseDaily({ items: [row], truncated: false })
  assert.equal(parsed.items[0].payableAmount, '100000.00')
  assert.ok(!JSON.stringify(parsed).includes('Private'))
  assert.ok(!JSON.stringify(parsed).includes('customerId'))
  assert.throws(() => api.parseDaily({ items: [{ ...row, customer: { phoneMasked: '0901234502', emailMasked: 'b***@example.test' } }], truncated: false }))
})
