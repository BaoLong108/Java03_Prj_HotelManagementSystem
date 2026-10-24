// API THẬT – GET /api/v1/bookings/{bookingCode}: origin/PhanThanhBinh@4d64567; origin/BaoLong@2fca3e4 grants STAFF/ADMIN read access.
// DTO ĐỀ XUẤT – chờ Thanh Binh (M3-03) chốt GET /api/v1/staff/bookings/search?phone= and masked contact/room metadata.
import { ApiError, apiRequest } from '../../../shared/api/client'
import { USE_FIXTURES } from '../shared/fixtureMode'
import { isBookingStatus } from '../shared/bookingStatus'
import { isOffsetDateTime } from '../shared/staffTime'
import { bookingQuery } from './bookingSearchFormat'
import type { BookingQuery, BookingSearchItem, BookingSearchResult } from './bookingSearchTypes'

const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === 'object' && !Array.isArray(value)
const id = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0
const text = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0
function invalid(): never { throw new ApiError(502, 'Dữ liệu booking từ máy chủ không hợp lệ.', 'INVALID_BOOKING_RESPONSE') }
function privateData(): never { throw new ApiError(502, 'Máy chủ trả thông tin khách chưa được che. Kết quả đã được ẩn; vui lòng báo người quản trị.', 'UNMASKED_CUSTOMER_DATA') }
function decimal(value: unknown, numeric: boolean, rate = false): string {
  if (numeric && (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 9999999999999.99)) return invalid()
  const result = numeric ? String(value) : value
  if (typeof result !== 'string' || !(rate ? /^\d{1,2}(\.\d{1,6})?$/ : /^\d{1,15}(\.\d{1,2})?$/).test(result)) return invalid()
  if (rate && Number(result) > 1) return invalid()
  return result
}
function common(raw: Record<string, unknown>, numeric: boolean) {
  if (!text(raw.bookingCode) || !isBookingStatus(raw.status) || !id(raw.guestCount)
      || !isOffsetDateTime(raw.startAt) || !isOffsetDateTime(raw.endAt) || Date.parse(raw.startAt) >= Date.parse(raw.endAt)
      || (raw.holdExpiresAt != null && !isOffsetDateTime(raw.holdExpiresAt))) return invalid()
  return { bookingCode: raw.bookingCode, status: raw.status, startAt: raw.startAt, endAt: raw.endAt,
    guestCount: raw.guestCount, ...(isOffsetDateTime(raw.holdExpiresAt) ? { holdExpiresAt: raw.holdExpiresAt } : {}),
    baseAmount: decimal(raw.baseAmount, numeric), discountRate: decimal(raw.discountRate, numeric, true), payableAmount: decimal(raw.payableAmount, numeric) }
}

/** Real Java BigDecimal values arrive as JSON numbers. Whitelist fields; never forward customerId or raw PII. */
export function parseBookingDetail(raw: unknown): BookingSearchItem {
  if (!record(raw) || !id(raw.id) || !id(raw.roomTypeId)) return invalid()
  const contact = record(raw.customer) ? raw.customer : raw
  const phone = contact.phone ?? contact.phoneNumber ?? raw.phone ?? raw.phoneNumber
  const email = contact.email ?? raw.email
  const serverPhone = contact.phoneMasked
  const serverEmail = contact.emailMasked
  const safePhone = text(serverPhone) && /^[+\d ()*-]+$/.test(serverPhone) && /\*{2,}/.test(serverPhone)
  const safeEmail = text(serverEmail) && /^[^\s@]*\*[^\s@]*@[^\s@]+\.[^\s@]+$/.test(serverEmail)
  const hasContact = [phone, email, serverPhone, serverEmail].some(text)
  const needsMask = text(phone) || text(email) || (text(serverPhone) && !safePhone) || (text(serverEmail) && !safeEmail)
  // Raw contact is never retained in the view model or storage. Ask the owner to mask at source.
  const phoneValue = text(phone) ? phone : text(serverPhone) ? serverPhone : ''
  const emailValue = text(email) ? email : text(serverEmail) ? serverEmail : ''
  const digits = phoneValue.replace(/\D/g, '')
  const maskedEmail = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(emailValue)
    ? `${emailValue[0]}***@${emailValue.split('@')[1]}` : '***'
  return { ...common(raw, true), bookingId: raw.id, roomTypeId: raw.roomTypeId,
    ...(text(raw.roomTypeName) ? { roomTypeName: raw.roomTypeName } : {}),
    ...(raw.roomNumber === null || text(raw.roomNumber) ? { roomNumber: raw.roomNumber } : {}),
    ...(hasContact ? { customer: { phoneMasked: safePhone ? serverPhone : phoneValue ? `*******${digits.length > 3 ? digits.slice(-3) : ''}` : '—',
      emailMasked: safeEmail ? serverEmail : emailValue ? maskedEmail : '—' } } : {}),
    ...(needsMask ? { clientMaskedContact: true } : {}) }
}

/** Proposed search contract: amounts are decimal strings; contact values must already be masked by the server. */
export function parseBookingSearch(raw: unknown): BookingSearchResult {
  if (!record(raw) || !Array.isArray(raw.items)) return invalid()
  const seen = new Set<number>()
  return { items: raw.items.map(item => {
    if (!record(item) || !id(item.bookingId) || seen.has(item.bookingId) || !text(item.roomTypeName)
        || !(item.roomNumber === null || text(item.roomNumber)) || !record(item.customer)) return invalid()
    const { phoneMasked, emailMasked } = item.customer
    if (['phone', 'email'].some(key => item[key] != null || (item.customer as Record<string, unknown>)[key] != null)
        || typeof phoneMasked !== 'string' || !/^[+\d ()*-]+$/.test(phoneMasked) || !/\*{2,}/.test(phoneMasked)
        || typeof emailMasked !== 'string' || !/^[^\s@]*\*[^\s@]*@[^\s@]+\.[^\s@]+$/.test(emailMasked)) return privateData()
    seen.add(item.bookingId)
    // fullName is deliberately omitted: the proposal supplies a full name, not a masked name.
    return { ...common(item, false), bookingId: item.bookingId, roomTypeName: item.roomTypeName, roomNumber: item.roomNumber,
      customer: { phoneMasked, emailMasked } }
  }) }
}

export async function searchBookings(query: BookingQuery, signal?: AbortSignal): Promise<BookingSearchResult> {
  const keys = Object.keys(query)
  if (keys.length !== 1 || !['bookingCode', 'phone'].includes(keys[0]) || typeof Object.values(query)[0] !== 'string') {
    throw new ApiError(400, 'Chọn đúng một cách tìm: mã booking hoặc số điện thoại.')
  }
  const normalized = 'bookingCode' in query && query.bookingCode !== undefined ? bookingQuery('bookingCode', query.bookingCode) : bookingQuery('phone', query.phone!)
  if (USE_FIXTURES) {
    const { bookingSearchFixture } = await import('./bookingSearch.fixture')
    signal?.throwIfAborted()
    return parseBookingSearch(bookingSearchFixture(normalized))
  }
  if (normalized.bookingCode !== undefined) {
    try {
      const raw = await apiRequest<unknown>(`/api/v1/bookings/${encodeURIComponent(normalized.bookingCode)}`, { signal })
      const item = parseBookingDetail(raw)
      if (item.bookingCode !== normalized.bookingCode) return invalid()
      return { items: [item] }
    } catch (error) {
      // A missing route/other 404 is an integration error, never an empty search result.
      if (error instanceof ApiError && error.status === 404 && error.code === 'BOOKING_NOT_FOUND') return { items: [] }
      throw error
    }
  }
  throw new ApiError(501, 'Chờ API tìm theo SĐT (Thanh Binh)', 'PHONE_SEARCH_UNAVAILABLE')
}
