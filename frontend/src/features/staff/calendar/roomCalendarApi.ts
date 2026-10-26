// dev@b6bbe14: ADMIN reads types/physical rooms; STAFF/ADMIN reads blocks.
// Booking calendar is still pending M2-06. Only this adapter needs replacing when it exists.
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

function parseEntries(raw: unknown[]): CalendarEntry[] {
  const entries = raw.map(parseEntry)
  if (new Set(entries.map(entry => `${entry.kind}:${entry.id}`)).size !== entries.length) return invalid()
  return entries
}

/** Whitelist calendar fields: customer identity is never part of this view model. */
export function parseRoomCalendar(raw: unknown, query: CalendarQuery): RoomCalendar {
  if (!record(raw) || !isOffsetDateTime(raw.from) || !isOffsetDateTime(raw.to) || !Array.isArray(raw.rooms)
      || Date.parse(raw.from) !== Date.parse(query.from) || Date.parse(raw.to) !== Date.parse(query.to)) return invalid()
  const seen = new Set<number>()
  const rooms = raw.rooms.map(room => {
    if (!record(room) || !id(room.physicalRoomId) || seen.has(room.physicalRoomId) || !text(room.roomNumber)
        || !id(room.roomTypeId) || !text(room.roomTypeName) || typeof room.isActive !== 'boolean'
        || !(room.floorNumber === null || Number.isInteger(room.floorNumber)) || !Array.isArray(room.entries)
        || (query.roomTypeId !== undefined && room.roomTypeId !== query.roomTypeId)) return invalid()
    seen.add(room.physicalRoomId)
    return { physicalRoomId: room.physicalRoomId, roomNumber: room.roomNumber, floorNumber: room.floorNumber as number | null,
      roomTypeId: room.roomTypeId, roomTypeName: room.roomTypeName, isActive: room.isActive, entries: parseEntries(room.entries) }
  })
  const roomTypes = raw.roomTypes ?? Array.from(new Map(rooms.map(room => [room.roomTypeId, { id: room.roomTypeId, name: room.roomTypeName }])).values())
  if (!Array.isArray(roomTypes) || roomTypes.some(type => !record(type) || !id(type.id) || !text(type.name))
      || new Set(roomTypes.map(type => type.id)).size !== roomTypes.length) return invalid()
  return { from: raw.from, to: raw.to, rooms, roomTypes: roomTypes.map(type => ({ id: type.id, name: type.name })) }
}

/** Compose existing dev endpoints without inferring availability or synthesizing bookings. */
export async function getRealRoomCalendar(query: CalendarQuery, signal?: AbortSignal): Promise<RoomCalendar> {
  const params = new URLSearchParams({ from: query.from, to: query.to })
  if (query.roomTypeId !== undefined) params.set('roomTypeId', String(query.roomTypeId))
  const raw = await apiRequest<unknown>(`/api/v1/staff/room-calendar?${params}`, { signal })
  return parseRoomCalendar(raw, query)
}

export async function getRoomCalendar(query: CalendarQuery, signal?: AbortSignal): Promise<RoomCalendar> {
  if (!isOffsetDateTime(query.from) || !isOffsetDateTime(query.to) || Date.parse(query.from) >= Date.parse(query.to)
      || (query.roomTypeId !== undefined && !id(query.roomTypeId))) throw new ApiError(400, 'Bộ lọc lịch phòng không hợp lệ.')
  if (USE_FIXTURES) {
    const { roomCalendarFixture } = await import('./roomCalendar.fixture')
    signal?.throwIfAborted()
    return parseRoomCalendar(roomCalendarFixture(query), query)
  }
  return getRealRoomCalendar(query, signal)
}
