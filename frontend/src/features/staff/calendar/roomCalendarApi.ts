// DTO ĐỀ XUẤT – chờ Phước Sang (M2-06), phối hợp Thanh Binh, chốt API lịch phòng.
// Checked remote branches on 2026-10-22, including origin/PhuocSang@44174a6 and origin/BaoLong@2fca3e4.
import { ApiError, apiRequest } from '../../../shared/api/client'
import { USE_FIXTURES } from '../shared/fixtureMode'
import { isBookingStatus } from '../shared/bookingStatus'
import { isOffsetDateTime } from '../shared/staffTime'
import type { CalendarEntry, CalendarQuery, RoomCalendar } from './roomCalendarTypes'

const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === 'object' && !Array.isArray(value)
const id = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0
const text = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0
function invalid(): never { throw new ApiError(502, 'Dữ liệu lịch phòng từ máy chủ không hợp lệ.', 'INVALID_CALENDAR_RESPONSE') }

function parseEntry(raw: unknown): CalendarEntry {
  if (!record(raw) || !id(raw.id) || !isOffsetDateTime(raw.startAt) || !isOffsetDateTime(raw.endAt)
      || Date.parse(raw.startAt) >= Date.parse(raw.endAt)) return invalid()
  const interval = { id: raw.id, startAt: raw.startAt, endAt: raw.endAt }
  if (raw.kind === 'BLOCK' && text(raw.reason)) return {
    ...interval, kind: 'BLOCK', reason: raw.reason,
    ...(text(raw.createdByName) ? { createdByName: raw.createdByName } : {}),
  }
  if (raw.kind !== 'BOOKING' || !text(raw.bookingCode) || !isBookingStatus(raw.status)) return invalid()
  if (raw.holdExpiresAt != null && !isOffsetDateTime(raw.holdExpiresAt)) return invalid()
  return { ...interval, kind: 'BOOKING', bookingCode: raw.bookingCode, status: raw.status,
    ...(isOffsetDateTime(raw.holdExpiresAt) ? { holdExpiresAt: raw.holdExpiresAt } : {}) }
}

/** Whitelist calendar fields: customer identity is never part of this view model. */
export function parseRoomCalendar(raw: unknown, query: CalendarQuery): RoomCalendar {
  if (!record(raw) || !isOffsetDateTime(raw.from) || !isOffsetDateTime(raw.to) || !Array.isArray(raw.rooms)
      || Date.parse(raw.from) !== Date.parse(query.from) || Date.parse(raw.to) !== Date.parse(query.to)) return invalid()
  const seen = new Set<number>()
  return { from: raw.from, to: raw.to, rooms: raw.rooms.map(room => {
    if (!record(room) || !id(room.physicalRoomId) || seen.has(room.physicalRoomId) || !text(room.roomNumber)
        || !id(room.roomTypeId) || !text(room.roomTypeName) || typeof room.isActive !== 'boolean'
        || !(room.floorNumber === null || Number.isInteger(room.floorNumber)) || !Array.isArray(room.entries)) return invalid()
    seen.add(room.physicalRoomId)
    return { physicalRoomId: room.physicalRoomId, roomNumber: room.roomNumber, floorNumber: room.floorNumber as number | null,
      roomTypeId: room.roomTypeId, roomTypeName: room.roomTypeName, isActive: room.isActive, entries: room.entries.map(parseEntry) }
  }) }
}

export async function getRoomCalendar(query: CalendarQuery, signal?: AbortSignal): Promise<RoomCalendar> {
  if (!isOffsetDateTime(query.from) || !isOffsetDateTime(query.to) || Date.parse(query.from) >= Date.parse(query.to)
      || (query.roomTypeId !== undefined && !id(query.roomTypeId))) throw new ApiError(400, 'Bộ lọc lịch phòng không hợp lệ.')
  if (USE_FIXTURES) {
    const { roomCalendarFixture } = await import('./roomCalendar.fixture')
    signal?.throwIfAborted()
    return parseRoomCalendar(roomCalendarFixture(query), query)
  }
  const params = new URLSearchParams({ from: query.from, to: query.to })
  if (query.roomTypeId !== undefined) params.set('roomTypeId', String(query.roomTypeId))
  const response = await apiRequest<unknown>(`/api/v1/staff/room-calendar?${params}`, { signal })
  return parseRoomCalendar(response, query)
}
