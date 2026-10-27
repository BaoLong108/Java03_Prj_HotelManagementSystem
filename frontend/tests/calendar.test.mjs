import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import { pathToFileURL, URL } from 'node:url'
import { build } from 'vite'

const modulesDirectory = resolve('node_modules')
const output = await mkdtemp(resolve(modulesDirectory, '.m5-04-tests-'))
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
  build: { ssr: 'tests/calendar.entry.ts', outDir: output, rollupOptions: { output: { entryFileNames: 'entry.mjs' } } } })
const api = await import(pathToFileURL(resolve(output, 'entry.mjs')).href)
const range = api.calendarRange('2026-10-08')
const date = (day, time = '00:00') => `2026-10-${day}T${time}:00+07:00`
const near = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-9, `${actual} != ${expected}`)
const booking = (id, startAt, endAt, status = 'CONFIRMED') => ({ kind: 'BOOKING', id, bookingCode: `B${id}`, status, startAt, endAt })

test('calendar positions a same-day interval by hours, not rounded dates', () => {
  const position = api.positionCalendarBar(date('08','12:00'), date('08','18:00'), range.from, range.to)
  near(position.leftPercent, 12 / 168 * 100)
  near(position.widthPercent, 6 / 168 * 100)
})
test('calendar positions a multi-day interval with exact checkout time', () => {
  const position = api.positionCalendarBar(date('08','12:00'), date('10','10:00'), range.from, range.to)
  near(position.widthPercent, 46 / 168 * 100)
})
test('calendar clips starts before range and ends after range', () => {
  const position = api.positionCalendarBar(date('07','12:00'), date('16','10:00'), range.from, range.to)
  assert.deepEqual(position, { leftPercent: 0, widthPercent: 100, clippedStart: true, clippedEnd: true })
  near(api.positionCalendarBar(date('07'), date('08','12:00'), range.from, range.to).widthPercent, 12 / 168 * 100)
})
test('half-open edges outside the view do not draw bars', () => {
  assert.equal(api.positionCalendarBar(date('07'), range.from, range.from, range.to), null)
  assert.equal(api.positionCalendarBar(range.to, date('16'), range.from, range.to), null)
  assert.equal(api.positionCalendarBar(date('09'), date('09'), range.from, range.to), null)
  assert.equal(api.positionCalendarBar('bad', date('09'), range.from, range.to), null)
})
test('adjacent stays keep their 2-hour gap and share a row without overlap', () => {
  const bars = api.visibleCalendarEntries([
    booking(1, date('08','12:00'), date('09','10:00')),
    booking(2, date('09','12:00'), date('10','10:00')),
  ], range.from, range.to)
  assert.deepEqual(bars.map(bar => bar.lane), [0, 0])
  near(bars[1].position.leftPercent - bars[0].position.leftPercent - bars[0].position.widthPercent, 2 / 168 * 100)
})
test('intervals touching exactly do not overlap and genuinely overlapping records remain visible', () => {
  const bars = api.visibleCalendarEntries([
    booking(1, date('08','12:00'), date('09','10:00')),
    booking(2, date('09','10:00'), date('10','10:00')),
    { kind: 'BLOCK', id: 3, startAt: date('09','12:00'), endAt: date('10'), reason: 'Test' },
  ], range.from, range.to)
  assert.deepEqual(bars.map(bar => bar.lane), [0, 0, 1])
})
test('CANCELLED and EXPIRED are hidden; pending holds remain server-authoritative even after expiry time', () => {
  const entries = ['CANCELLED','EXPIRED','PENDING_PAYMENT','COMPLETED'].map((status, i) => ({
    ...booking(i + 1, date('08'), date('09'), status), holdExpiresAt: date('07'),
  }))
  assert.deepEqual(api.visibleCalendarEntries(entries, range.from, range.to).map(bar => bar.entry.status), ['PENDING_PAYMENT','COMPLETED'])
})
test('hotel dates, leap days and ISO requests are independent of system timezone', () => {
  assert.equal(api.hotelDate(new Date('2026-10-21T18:00:00Z')), '2026-10-22')
  assert.equal(api.isHotelDate('2026-02-29'), false)
  assert.equal(api.isHotelDate('2028-02-29'), true)
  assert.equal(api.addHotelDays('2026-12-31', 1), '2027-01-01')
  assert.deepEqual(range, { from: date('08'), to: date('15') })
  assert.equal(api.formatHotelTime('2026-10-08T05:00:00Z'), '08/10/2026 12:00')
  assert.equal(api.isOffsetDateTime('2026-10-08T12:00:00'), false)
})
test('fixture has 6 physical rooms, 2 types, inactive room, booking/block and optional filtering', () => {
  const result = api.roomCalendarFixture(range)
  assert.equal(result.rooms.length, 6)
  assert.equal(new Set(result.rooms.map(room => room.roomTypeId)).size, 2)
  assert.ok(result.rooms.some(room => !room.isActive))
  assert.equal(api.roomCalendarFixture({ ...range, roomTypeId: 2 }).rooms.length, 3)
  assert.equal(api.roomCalendarFixture({ ...range, roomTypeId: 999 }).rooms.length, 0)
})
test('calendar parser rejects malformed records and never forwards extra customer data', () => {
  const fixture = api.roomCalendarFixture(range)
  fixture.rooms[0].entries[0].customer = { fullName: 'Private', phone: '0901234567' }
  const parsed = api.parseRoomCalendar(fixture, range)
  assert.equal('customer' in parsed.rooms[0].entries[0], false)
  assert.throws(() => api.parseRoomCalendar({ ...fixture, to: date('16') }, range), /không hợp lệ/)
  assert.throws(() => api.parseRoomCalendar({ ...fixture, rooms: [{}] }, range), /không hợp lệ/)
})

test('real aggregate calendar uses one authenticated request and keeps room filters', async () => {
  const token = `header.${Buffer.from(JSON.stringify({ exp: Math.floor(Date.now()/1000) + 3600 })).toString('base64url')}.signature`
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: { getItem: () => JSON.stringify({ accessToken: token, expiresAt: Date.now() + 3600000 }) } })
  const query = { ...range, roomTypeId: 2 }
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push(url)
    assert.equal(options.headers.Authorization, `Bearer ${token}`)
    return Response.json(api.roomCalendarFixture(query))
  }
  const result = await api.getRoomCalendar(query)
  assert.equal(result.rooms.length, 3)
  assert.ok(result.rooms.every(room => room.roomTypeId === 2))
  const request = new URL(calls[0], 'http://localhost')
  assert.equal(request.pathname, '/api/v1/staff/room-calendar')
  assert.equal(request.searchParams.get('from'), range.from)
  assert.equal(request.searchParams.get('roomTypeId'), '2')
  assert.equal(calls.length, 1)
})
test('aggregate calendar errors never become available rooms or fixtures', async () => {
  globalThis.fetch = async () => Response.json({ message: 'Unavailable' }, { status: 500 })
  await assert.rejects(api.getRoomCalendar(range), /Unavailable/)
})
test('duplicate rooms or events and malformed server intervals are rejected', async () => {
  const raw = api.roomCalendarFixture(range)
  const duplicate = globalThis.structuredClone(raw); duplicate.rooms.push(duplicate.rooms[0])
  assert.throws(() => api.parseRoomCalendar(duplicate, range), /không hợp lệ/)
  const event = globalThis.structuredClone(raw); event.rooms[0].entries.push(event.rooms[0].entries[0])
  assert.throws(() => api.parseRoomCalendar(event, range), /không hợp lệ/)
  const malformed = globalThis.structuredClone(raw); malformed.rooms[0].entries[0].endAt = 'invalid'
  assert.throws(() => api.parseRoomCalendar(malformed, range), /không hợp lệ/)
})
test('permission failures and aborts propagate without fallback', async () => {
  const savedWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { dispatchEvent: () => true } })
  for (const status of [401, 403]) {
    globalThis.fetch = async () => Response.json({ message: 'Denied' }, { status })
    await assert.rejects(api.getRoomCalendar(range), error => error.status === status)
  }
  const controller = new AbortController(); controller.abort(new Error('aborted'))
  globalThis.fetch = async (_, options) => { options.signal.throwIfAborted() }
  await assert.rejects(api.getRoomCalendar(range, controller.signal), /aborted/)
  if (savedWindow) Object.defineProperty(globalThis, 'window', savedWindow)
  else delete globalThis.window
})
test('empty aggregate results are accepted and invalid query does not call server', async () => {
  globalThis.fetch = async () => Response.json({ ...range, rooms: [], roomTypes: [] })
  assert.deepEqual((await api.getRoomCalendar(range)).rooms, [])
  globalThis.fetch = async () => { throw new Error('must not call') }
  await assert.rejects(api.getRoomCalendar({ ...range, from: range.to }), error => error.status === 400)
})
