import type { BookingStatus } from '../shared/bookingStatus'

type Interval = { id: number; startAt: string; endAt: string }
export type CalendarEntry = (Interval & {
  kind: 'BOOKING'; bookingCode: string; status: BookingStatus; holdExpiresAt?: string
}) | (Interval & { kind: 'BLOCK'; reason: string; createdByName?: string })
export type CalendarRoom = {
  physicalRoomId: number; roomNumber: string; floorNumber: number | null
  roomTypeId: number; roomTypeName: string; isActive: boolean; entries: CalendarEntry[]; blockError?: string
}
export type RoomCalendar = { from: string; to: string; rooms: CalendarRoom[]; roomTypes?: { id: number; name: string }[] }
export type CalendarQuery = { from: string; to: string; roomTypeId?: number }
