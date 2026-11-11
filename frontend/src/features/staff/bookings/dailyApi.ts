import { ApiError, apiRequest } from '../../../shared/api/client'
import { isBookingStatus } from '../shared/bookingStatus'
import type { BookingStatus } from '../shared/bookingStatus'
import { hotelDate, isHotelDate, isOffsetDateTime } from '../shared/staffTime'

export type DailyFilters = { date: string; roomTypeId: string; status: string }
export type DailyBooking = { bookingCode: string; status: BookingStatus; roomTypeId: number; roomTypeName: string; roomNumber: string; startAt: string; endAt: string; guestCount: number; holdExpiresAt: string; payableAmount: string; customer: { phoneMasked: string; emailMasked: string } }
export function dailyFilters(params: URLSearchParams): DailyFilters {
  return { date: params.get('date') ?? hotelDate(), roomTypeId: params.get('roomTypeId') ?? '', status: params.get('status') ?? '' }
}
export function dailyQuery(filters: DailyFilters) {
  if (!isHotelDate(filters.date) || (filters.status && !isBookingStatus(filters.status)) || (filters.roomTypeId && (!/^[1-9]\d*$/.test(filters.roomTypeId) || !Number.isSafeInteger(Number(filters.roomTypeId))))) throw new Error('Bộ lọc ngày, loại phòng hoặc trạng thái không hợp lệ.')
  const params = new URLSearchParams({ date: filters.date })
  if (filters.roomTypeId) params.set('roomTypeId', filters.roomTypeId)
  if (filters.status) params.set('status', filters.status)
  return params
}
const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === 'object'
export function parseDaily(raw: unknown): { items: DailyBooking[]; truncated: boolean } {
  const invalid = (): never => { throw new ApiError(502, 'Dữ liệu theo ngày không hợp lệ hoặc chưa được che.', 'INVALID_REPORT') }
  if (!record(raw) || !Array.isArray(raw.items) || typeof raw.truncated !== 'boolean') return invalid()
  const items = raw.items.map(row => {
    if (!record(row) || typeof row.bookingCode !== 'string' || !isBookingStatus(row.status) || !Number.isSafeInteger(row.roomTypeId) || Number(row.roomTypeId) < 1 || typeof row.roomTypeName !== 'string' || typeof row.roomNumber !== 'string' || !isOffsetDateTime(row.startAt) || !isOffsetDateTime(row.endAt) || !isOffsetDateTime(row.holdExpiresAt) || !Number.isInteger(row.guestCount) || Number(row.guestCount) < 1 || typeof row.payableAmount !== 'string' || !/^\d{1,15}(\.\d{1,2})?$/.test(row.payableAmount) || !record(row.customer) || typeof row.customer.phoneMasked !== 'string' || !/^[\d* +()-]*\*{2,}[\d* +()-]*$/.test(row.customer.phoneMasked) || typeof row.customer.emailMasked !== 'string' || !row.customer.emailMasked.includes('*')) return invalid()
    return { bookingCode: row.bookingCode, status: row.status, roomTypeId: Number(row.roomTypeId), roomTypeName: row.roomTypeName, roomNumber: row.roomNumber, startAt: row.startAt, endAt: row.endAt, holdExpiresAt: row.holdExpiresAt, guestCount: Number(row.guestCount), payableAmount: row.payableAmount, customer: { phoneMasked: row.customer.phoneMasked, emailMasked: row.customer.emailMasked } }
  })
  return { items, truncated: raw.truncated }
}
export async function getDaily(filters: DailyFilters, signal?: AbortSignal) {
  return parseDaily(await apiRequest<unknown>(`/api/v1/staff/bookings/daily?${dailyQuery(filters)}`, { signal }))
}
