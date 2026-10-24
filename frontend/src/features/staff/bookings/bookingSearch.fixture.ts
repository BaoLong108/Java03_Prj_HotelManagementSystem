import { roomCalendarFixture } from '../calendar/roomCalendar.fixture'
import type { BookingQuery } from './bookingSearchTypes'

// Synthetic development records. Enabled only by VITE_USE_FIXTURES=true, never an HTTP-error fallback.
const rooms = roomCalendarFixture({ from: '2026-10-01T00:00:00+07:00', to: '2026-11-01T00:00:00+07:00' }).rooms
const items = rooms.flatMap(room => room.entries.flatMap(entry => entry.kind === 'BOOKING' ? [{
  bookingId: entry.id, bookingCode: entry.bookingCode, status: entry.status,
  roomTypeName: room.roomTypeName, roomNumber: room.roomNumber,
  startAt: entry.startAt, endAt: entry.endAt, guestCount: 2,
  ...(entry.holdExpiresAt ? { holdExpiresAt: entry.holdExpiresAt } : {}),
  baseAmount: '1200000.00', discountRate: '0.10', payableAmount: '1080000.00',
  customer: { fullName: 'Khách mẫu', phoneMasked: '090****123', emailMasked: 'k***@example.test' },
}] : []))

export function bookingSearchFixture(query: BookingQuery) {
  const matched = query.bookingCode !== undefined ? items.filter(item => item.bookingCode === query.bookingCode)
    : query.phone === '0900000123' ? items : []
  return { items: structuredClone(matched) }
}
