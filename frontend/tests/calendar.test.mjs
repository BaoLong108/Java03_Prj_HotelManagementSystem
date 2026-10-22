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
const physical = (id) => ({ id, roomTypeId: 2, roomTypeName: 'Deluxe', roomNumber: String(id), floorNumber: 1, active: true })
const block = (id, roomId, startAt = date('08','12:00'), endAt = date('09','10:00'), active = true) =>
  ({ id, physicalRoomId: roomId, startAt, endAt, active, reason: 'Bảo trì thật', createdByUserId: 1 })
function catalogFetch(blockResponse, rooms = [physical(21)], calls = []) {
  return async (url, options) => {
    calls.push(url)
    assert.ok(options.headers.Authorization?.startsWith('Bearer '))
    if (url === '/api/v1/admin/rooms/types') return Response.json([{ id: 2, name: 'Deluxe' }, { id: 3, name: 'Suite' }])
    assert.ok(!url.includes('roomTypeId=3'), 'Filtered query must not load another room type')
    if (url === '/api/v1/admin/rooms/physical?roomTypeId=2') return Response.json(rooms)
    return blockResponse(Number(new URL(url, 'http://localhost').searchParams.get('roomId')), options)
  }
}
test('real adapter joins dev types/rooms/blocks once per selected room, with session token and half-open filtering', async () => {
  const token = `header.${Buffer.from(JSON.stringify({ exp: Math.floor(Date.now()/1000) + 3600 })).toString('base64url')}.signature`
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: { getItem: () => JSON.stringify({ accessToken: token, expiresAt: Date.now() + 3600000 }) } })
  const calls = []
  globalThis.fetch = catalogFetch(roomId => Response.json([
    block(1, roomId), block(2, roomId, date('07'), range.from), block(3, roomId, range.to, date('16')),
    block(4, roomId, date('08'), date('09'), false),
  ]), [physical(21)], calls)
  const result = await api.getRoomCalendar({ ...range, roomTypeId: 2 })
  assert.equal(result.rooms[0].roomTypeName, 'Deluxe')
  assert.deepEqual(result.rooms[0].entries, [{ kind: 'BLOCK', id: 1, startAt: date('08','12:00'), endAt: date('09','10:00'), reason: 'Bảo trì thật' }])
  assert.equal(result.roomTypes.length, 2)
  assert.deepEqual(calls, ['/api/v1/admin/rooms/types', '/api/v1/admin/rooms/physical?roomTypeId=2', '/api/v1/admin/room-blocks?roomId=21&history=false'])
})
test('a failed block request keeps other rooms and marks the failed row as unknown', async () => {
  globalThis.fetch = catalogFetch(roomId => roomId === 21 ? Response.json([block(1, roomId)]) : Response.json({ message: 'Unavailable' }, { status: 500 }), [physical(21), physical(22)])
  const result = await api.getRoomCalendar({ ...range, roomTypeId: 2 })
  assert.equal(result.rooms[0].entries.length, 1)
  assert.equal(result.rooms[0].blockError, undefined)
  assert.equal(result.rooms[1].entries.length, 0)
  assert.match(result.rooms[1].blockError, /Chưa tải được/)
})
test('wrong-room, duplicate or malformed blocks are not rendered as valid data', async () => {
  for (const blocks of [[block(1, 999)], [block(1, 21), block(1, 21)], [{ ...block(1,21), endAt: 'bad' }]]) {
    globalThis.fetch = catalogFetch(() => Response.json(blocks))
    const result = await api.getRoomCalendar({ ...range, roomTypeId: 2 })
    assert.equal(result.rooms[0].entries.length, 0)
    assert.ok(result.rooms[0].blockError)
  }
})
test('permission failures and aborts propagate instead of becoming an empty calendar', async () => {
  globalThis.fetch = catalogFetch(() => Response.json({ code: 'FORBIDDEN', message: 'Không đủ quyền' }, { status: 403 }))
  await assert.rejects(api.getRoomCalendar({ ...range, roomTypeId: 2 }), error => error.status === 403)
  const controller = new AbortController()
  globalThis.fetch = catalogFetch(() => { controller.abort(); throw new Error('aborted') })
  await assert.rejects(api.getRoomCalendar({ ...range, roomTypeId: 2 }, controller.signal), error => error.name === 'AbortError')
})
test('unknown type makes no physical-room or block requests and catalog errors never use fixtures', async () => {
  let calls = 0
  globalThis.fetch = async () => { calls++; return Response.json([{ id: 2, name: 'Deluxe' }]) }
  assert.equal((await api.getRoomCalendar({ ...range, roomTypeId: 999 })).rooms.length, 0)
  assert.equal(calls, 1)
  globalThis.fetch = async () => Response.json({ message: 'Catalog unavailable' }, { status: 500 })
  await assert.rejects(api.getRoomCalendar(range), /Catalog unavailable/)
  globalThis.fetch = async () => Response.json([{ id: 2, name: 'Deluxe' }, { id: 2, name: 'Duplicate' }])
  await assert.rejects(api.getRoomCalendar(range), /không hợp lệ/)
})
