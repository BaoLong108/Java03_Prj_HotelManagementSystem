import test from 'node:test'
import assert from 'node:assert/strict'
import { formatHoldTime, holdSecondsRemaining } from '../src/features/booking/hold.ts'

test('hold counts from the server deadline; it never starts a fresh 15 minutes on reload', () => {
  const now = Date.parse('2026-10-16T12:00:00+07:00')
  assert.equal(holdSecondsRemaining('2026-10-16T12:10:00+07:00', now), 600)
  assert.equal(holdSecondsRemaining('2026-10-16T05:10:00Z', now), 600)
  assert.equal(formatHoldTime(600), '10:00')
})
test('tab suspension past the deadline returns zero rather than a negative/renewed hold', () => {
  assert.equal(holdSecondsRemaining('2026-10-16T05:10:00Z', Date.parse('2026-10-16T05:11:00Z')), 0)
  assert.equal(holdSecondsRemaining('2026-10-16T05:10:00Z', Date.parse('2026-10-16T05:10:00Z')), 0)
})
test('unknown or timezone-less deadlines must not authorize a payment window', () => {
  assert.equal(holdSecondsRemaining('not-a-date'), null)
  assert.equal(holdSecondsRemaining('2026-10-16T12:10:00'), null)
  assert.equal(holdSecondsRemaining('2026-10-16T05:10:00Z', NaN), null)
})
