import test from 'node:test'
import assert from 'node:assert/strict'
import { URLSearchParams } from 'node:url'
import { hotelToday, validDate, addDays, nightsBetween, readStay, stayParams, validateStay } from '../src/features/rooms/search.ts'

test('hotel date follows Vietnam when UTC is still the previous day', () => {
  assert.equal(hotelToday(new Date('2026-10-14T18:30:00Z')), '2026-10-15')
})
test('calendar validation rejects rollover dates, malformed strings and invalid leap days', () => {
  for (const value of ['2026-02-29', '2026-04-31', '2026-13-01', '15/10/2026', '', '2026-10-15T00:00:00Z']) assert.equal(validDate(value), false, value)
  assert.equal(validDate('2028-02-29'), true)
})
test('next day handles month and year boundaries', () => {
  assert.equal(addDays('2026-12-31', 1), '2027-01-01')
  assert.equal(addDays('2026-11-01', -7), '2026-10-25')
  assert.equal(addDays('', 1), '')
})
test('nights require a strictly positive interval, without local DST arithmetic', () => {
  assert.equal(nightsBetween('2026-10-31', '2026-11-02'), 2)
  assert.equal(nightsBetween('2026-10-15', '2026-10-15'), null)
  assert.equal(nightsBetween('2026-10-16', '2026-10-15'), null)
  assert.equal(nightsBetween('not-a-date', '2026-10-15'), null)
})
test('dates from pasted URLs are checked before a search can be accepted', () => {
  const stay = readStay(new URLSearchParams('arrival=2026-10-01&departure=2026-09-30'), '2026-10-15')
  assert.deepEqual(Object.keys(validateStay(stay, '2026-10-15')), ['arrival', 'departure'])
})
test('untrusted guest counts are bounded; zero children remains valid', () => {
  assert.deepEqual(readStay(new URLSearchParams('adults=-1&children=999'), '2026-10-15'), { arrival: '2026-10-15', departure: '2026-10-16', adults: 2, children: 0 })
  assert.equal(readStay(new URLSearchParams('adults=2.5')).adults, 2)
  assert.equal(readStay(new URLSearchParams('children=0')).children, 0)
})
test('form validation prevents zero adults and fractional/oversized counts', () => {
  const base = { arrival: '2026-10-15', departure: '2026-10-16', adults: 2, children: 0 }
  assert.deepEqual(validateStay(base, '2026-10-15'), {})
  for (const change of [{ adults: 0 }, { adults: 11 }, { children: -1 }, { children: 1.5 }]) assert.ok(validateStay({ ...base, ...change }, '2026-10-15').guests)
})
test('search share/back navigation round-trips dates and parties without price or credentials', () => {
  const stay = { arrival: '2026-10-20', departure: '2026-10-23', adults: 3, children: 2 }
  const serialized = stayParams(stay)
  assert.deepEqual(readStay(new URLSearchParams(serialized)), stay)
  assert.deepEqual([...new URLSearchParams(serialized).keys()], ['arrival', 'departure', 'adults', 'children'])
})
