import { apiRequest } from '../../shared/api/client'
import type { BookingResponse, CreateBookingRequest, PriceQuote, RoomType } from './types'

const params = (body: CreateBookingRequest) => new URLSearchParams({
  roomTypeId: String(body.roomTypeId), startAt: body.startAt, endAt: body.endAt, guestCount: String(body.guestCount),
})
export const bookingApi = {
  downloadConfirmation: async (code: string) => {
    const blob = await apiRequest<Blob>(`/api/v1/bookings/${encodeURIComponent(code)}/confirmation.pdf`, { responseType: 'blob' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url; link.download = `booking-${code}.pdf`; link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  },
  create: (body: CreateBookingRequest) => apiRequest<BookingResponse>('/api/v1/bookings', { method: 'POST', body }),
  getByCode: (code: string, signal?: AbortSignal) => apiRequest<BookingResponse>(`/api/v1/bookings/${encodeURIComponent(code)}`, { signal }),
  getMyBookings: (signal?: AbortSignal) => apiRequest<BookingResponse[]>('/api/v1/bookings', { signal }),
  cancel: (code: string) => apiRequest<BookingResponse>(`/api/v1/bookings/${encodeURIComponent(code)}/cancel`, { method: 'POST' }),
  confirm: async (code: string): Promise<BookingResponse> => {
    await apiRequest('/api/v1/payments/demo', { method: 'POST', body: { bookingCode: code, idempotencyKey: `web-demo-${code}` } })
    return bookingApi.getByCode(code)
  },
  getRoomTypes: (signal?: AbortSignal) => apiRequest<RoomType[]>('/api/v1/rooms', { signal, auth: false }),
  search: (body: CreateBookingRequest, signal?: AbortSignal) =>
    apiRequest<RoomType[]>(`/api/v1/rooms/availability?${params(body)}`, { signal, auth: false }),
  quote: async (room: RoomType, body: CreateBookingRequest, signal?: AbortSignal): Promise<PriceQuote & { appliedTier: string }> => {
    const result = await apiRequest<Omit<PriceQuote, 'roomType' | 'guestCount'> & { appliedTier: string }>(
      `/api/v1/pricing/quote?${params(body)}`, { signal })
    return { ...result, roomType: room, guestCount: body.guestCount }
  },
}
