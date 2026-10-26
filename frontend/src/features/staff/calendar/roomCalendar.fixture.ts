import { positionCalendarBar } from './calendarGeometry'
import type { CalendarQuery, CalendarRoom, RoomCalendar } from './roomCalendarTypes'

export const FIXTURE_CALENDAR_DATE = '2026-10-08'
const rooms: CalendarRoom[] = [
  { physicalRoomId: 101, roomNumber: '101', floorNumber: 1, roomTypeId: 1, roomTypeName: 'Standard', isActive: true, entries: [
    { kind: 'BOOKING', id: 1, bookingCode: 'BK-MAU-001', status: 'CONFIRMED', startAt: '2026-10-08T12:00:00+07:00', endAt: '2026-10-09T10:00:00+07:00' },
    { kind: 'BOOKING', id: 2, bookingCode: 'BK-MAU-002', status: 'CONFIRMED', startAt: '2026-10-09T12:00:00+07:00', endAt: '2026-10-10T10:00:00+07:00' },
  ] },
  { physicalRoomId: 102, roomNumber: '102', floorNumber: 1, roomTypeId: 1, roomTypeName: 'Standard', isActive: true, entries: [
    { kind: 'BOOKING', id: 3, bookingCode: 'BK-MAU-003', status: 'PENDING_PAYMENT', startAt: '2026-10-09T12:00:00+07:00', endAt: '2026-10-11T10:00:00+07:00', holdExpiresAt: '2026-10-08T11:45:00+07:00' },
  ] },
  { physicalRoomId: 103, roomNumber: '103', floorNumber: 1, roomTypeId: 1, roomTypeName: 'Standard', isActive: true, entries: [] },
  { physicalRoomId: 201, roomNumber: '201', floorNumber: 2, roomTypeId: 2, roomTypeName: 'Deluxe', isActive: true, entries: [
    { kind: 'BLOCK', id: 4, startAt: '2026-10-10T08:30:00+07:00', endAt: '2026-10-12T16:00:00+07:00', reason: 'Bảo trì điều hòa', createdByName: 'Nhân viên mẫu' },
  ] },
  { physicalRoomId: 202, roomNumber: '202', floorNumber: 2, roomTypeId: 2, roomTypeName: 'Deluxe', isActive: true, entries: [
    { kind: 'BOOKING', id: 5, bookingCode: 'BK-MAU-005', status: 'COMPLETED', startAt: '2026-10-07T12:00:00+07:00', endAt: '2026-10-09T10:00:00+07:00' },
    { kind: 'BOOKING', id: 6, bookingCode: 'BK-MAU-006', status: 'CANCELLED', startAt: '2026-10-10T12:00:00+07:00', endAt: '2026-10-11T10:00:00+07:00' },
    { kind: 'BOOKING', id: 7, bookingCode: 'BK-MAU-007', status: 'EXPIRED', startAt: '2026-10-12T12:00:00+07:00', endAt: '2026-10-13T10:00:00+07:00' },
  ] },
  { physicalRoomId: 203, roomNumber: '203', floorNumber: null, roomTypeId: 2, roomTypeName: 'Deluxe', isActive: false, entries: [] },
]

/** Explicit development data; this module is imported only when VITE_USE_FIXTURES=true. */
export function roomCalendarFixture(query: CalendarQuery): RoomCalendar {
  return { from: query.from, to: query.to, rooms: structuredClone(rooms)
    .filter(room => query.roomTypeId === undefined || room.roomTypeId === query.roomTypeId)
    .map(room => ({ ...room, entries: room.entries.filter(entry => positionCalendarBar(entry.startAt, entry.endAt, query.from, query.to)) })) }
}
