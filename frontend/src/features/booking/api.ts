import { apiRequest } from '../../shared/api/client'
import type { BookingResponse, CreateBookingRequest, PriceQuote, RoomType } from './types'
import { DEFAULT_ROOM_TYPES } from './types'

export const bookingApi = {
  /**
   * Tạo đơn giữ chỗ mới (Hold 15 phút, trạng thái PENDING_PAYMENT)
   * POST /api/v1/bookings
   */
  create: (body: CreateBookingRequest) =>
    apiRequest<BookingResponse>('/api/v1/bookings', { method: 'POST', body }),

  /**
   * Tra cứu chi tiết đơn đặt phòng theo mã bookingCode
   * GET /api/v1/bookings/{bookingCode}
   */
  getByCode: (bookingCode: string, signal?: AbortSignal) =>
    apiRequest<BookingResponse>(`/api/v1/bookings/${bookingCode}`, { signal }),

  /**
   * Lấy danh sách các đơn đặt phòng của chính tài khoản khách hàng hiện tại
   * GET /api/v1/bookings
   */
  getMyBookings: (signal?: AbortSignal) =>
    apiRequest<BookingResponse[]>('/api/v1/bookings', { signal }),

  /**
   * Hủy đơn đặt phòng (khi đang PENDING_PAYMENT hoặc CONFIRMED)
   * POST /api/v1/bookings/{bookingCode}/cancel
   */
  cancel: (bookingCode: string) =>
    apiRequest<BookingResponse>(`/api/v1/bookings/${bookingCode}/cancel`, { method: 'POST' }),

  /**
   * Xác nhận thanh toán đơn giữ chỗ thành công (chuyển sang CONFIRMED)
   * POST /api/v1/bookings/{bookingCode}/confirm
   */
  confirm: (bookingCode: string) =>
    apiRequest<BookingResponse>(`/api/v1/bookings/${bookingCode}/confirm`, { method: 'POST' }),

  /**
   * Hoàn tất đơn đặt phòng (Dành cho Staff/Admin)
   * POST /api/v1/bookings/{bookingCode}/complete
   */
  complete: (bookingCode: string) =>
    apiRequest<BookingResponse>(`/api/v1/bookings/${bookingCode}/complete`, { method: 'POST' }),

  /**
   * Lấy danh mục loại phòng khả dụng (thử fetch từ /api/v1/room-types, fallback sang DEFAULT_ROOM_TYPES)
   */
  getRoomTypes: async (signal?: AbortSignal): Promise<RoomType[]> => {
    try {
      const remote = await apiRequest<RoomType[]>('/api/v1/room-types', { signal, auth: false })
      if (Array.isArray(remote) && remote.length > 0) {
        return remote
      }
    } catch {
      // Endpoint /api/v1/room-types chưa sẵn sàng, dùng danh mục phòng tiêu chuẩn schema
    }
    return DEFAULT_ROOM_TYPES
  },
}

/**
 * Tính toán báo giá tức thì (Quote) dựa trên số đêm và chiết khấu hạng hội viên.
 */
export function calculateQuote(
  roomType: RoomType,
  checkInDateStr: string,
  checkOutDateStr: string,
  guestCount: number,
  tierCode: string = 'STANDARD',
): PriceQuote {
  const d1 = new Date(checkInDateStr)
  const d2 = new Date(checkOutDateStr)
  const diffTime = d2.getTime() - d1.getTime()
  const nights = Math.max(1, Math.round(diffTime / (1000 * 60 * 60 * 24)))

  const baseAmount = nights * roomType.basePricePerNight
  const discountRate = tierCode === 'DIAMOND' ? 0.15 : tierCode === 'VIP' ? 0.10 : 0.00
  const discountAmount = Math.round(baseAmount * discountRate)
  const payableAmount = baseAmount - discountAmount

  return {
    roomType,
    nights,
    guestCount,
    baseAmount,
    discountRate,
    discountAmount,
    payableAmount,
  }
}
