// dev@b6bbe14: ADMIN reads types/physical rooms; STAFF/ADMIN reads blocks.
// Booking calendar is still pending M2-06. Only this adapter needs replacing when it exists.
import { ApiError, apiRequest } from '../../../shared/api/client'
import { USE_FIXTURES } from '../shared/fixtureMode'
import { isBookingStatus } from '../shared/bookingStatus'
import { isOffsetDateTime } from '../shared/staffTime'
import type { CalendarEntry, CalendarQuery, CalendarRoom, RoomCalendar } from './roomCalendarTypes'

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

/** Compose existing dev endpoints without inferring availability or synthesizing bookings. */
export async function getRealRoomCalendar(query: CalendarQuery, signal?: AbortSignal): Promise<RoomCalendar> {
  const rawTypes = await apiRequest<unknown>('/api/v1/admin/rooms/types', { signal })
  if (!Array.isArray(rawTypes)) return invalid()
  const typeIds = new Set<number>()
  const roomTypes = rawTypes.map(type => {
    if (!record(type) || !id(type.id) || typeIds.has(type.id) || !text(type.name)) return invalid()
    typeIds.add(type.id)
    return { id: type.id, name: type.name }
  })
  const selectedTypes = roomTypes.filter(type => query.roomTypeId === undefined || type.id === query.roomTypeId)
  const groups = await Promise.all(selectedTypes.map(async type => {
    const rawRooms = await apiRequest<unknown>(`/api/v1/admin/rooms/physical?roomTypeId=${type.id}`, { signal })
    if (!Array.isArray(rawRooms)) return invalid()
    return rawRooms.map((raw): CalendarRoom => {
      if (!record(raw) || !id(raw.id) || raw.roomTypeId !== type.id || !text(raw.roomNumber)
          || typeof raw.active !== 'boolean' || !(raw.floorNumber === null || Number.isInteger(raw.floorNumber))) return invalid()
      return { physicalRoomId: raw.id, roomNumber: raw.roomNumber, roomTypeId: type.id, roomTypeName: type.name,
        floorNumber: raw.floorNumber as number | null, isActive: raw.active, entries: [] }
    })
  }))
  const rooms = groups.flat()
  if (new Set(rooms.map(room => room.physicalRoomId)).size !== rooms.length) return invalid()
  // One request per physical room, never one per day. Endpoint has no time-range filter.
  const loaded = await Promise.all(rooms.map(async room => {
    try {
      const rawBlocks = await apiRequest<unknown>(`/api/v1/admin/room-blocks?roomId=${room.physicalRoomId}&history=false`, { signal })
      if (!Array.isArray(rawBlocks)) return invalid()
      const seen = new Set<number>()
      const entries = rawBlocks.flatMap(raw => {
        if (!record(raw) || raw.physicalRoomId !== room.physicalRoomId || !id(raw.id) || seen.has(raw.id)
            || typeof raw.active !== 'boolean') return invalid()
        seen.add(raw.id)
        const entry = parseEntry({ ...raw, kind: 'BLOCK' })
        return raw.active && Date.parse(entry.startAt) < Date.parse(query.to) && Date.parse(entry.endAt) > Date.parse(query.from) ? [entry] : []
      })
      return { ...room, entries }
    } catch (error) {
      signal?.throwIfAborted()
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) throw error
      // Failed room remains explicitly unknown, while other rooms still render.
      return { ...room, blockError: 'Chưa tải được khoảng chặn. Hãy tải lại.' }
    }
  }))
  return { from: query.from, to: query.to, rooms: loaded, roomTypes }
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
