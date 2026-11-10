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
