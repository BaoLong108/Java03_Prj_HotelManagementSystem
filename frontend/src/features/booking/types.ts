export type BookingStatus = 'PENDING_PAYMENT' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED'

export type BookingResponse = {
  id: number
  bookingCode: string
  customerId: number
  roomTypeId: number
  guestCount: number
  startAt: string
  endAt: string
  holdExpiresAt: string
  status: BookingStatus
  baseAmount: number
  discountRate: number
  discountAmount: number
  payableAmount: number
  createdAt: string
  confirmedAt?: string | null
  completedAt?: string | null
  cancelledAt?: string | null
  expiredAt?: string | null
}

export type CreateBookingRequest = {
  roomTypeId: number
  guestCount: number
  startAt: string
  endAt: string
}

export type RoomType = {
  id: number
  code: string
  name: string
  description?: string
  maxOccupancy: number
  basePricePerNight: number
  imageUrl?: string
}

export type PriceQuote = {
  roomType: RoomType
  nights: number
  guestCount: number
  baseAmount: number
  discountRate: number
  discountAmount: number
  payableAmount: number
}

export const bookingStatusLabels: Record<BookingStatus, string> = {
  PENDING_PAYMENT: 'Chờ thanh toán (Hold 15p)',
  CONFIRMED: 'Đã xác nhận',
  COMPLETED: 'Đã hoàn tất',
  CANCELLED: 'Đã hủy',
  EXPIRED: 'Đã hết hạn',
}

export const bookingStatusTones: Record<BookingStatus, 'neutral' | 'success' | 'warning' | 'danger'> = {
  PENDING_PAYMENT: 'warning',
  CONFIRMED: 'success',
  COMPLETED: 'neutral',
  CANCELLED: 'danger',
  EXPIRED: 'danger',
}

export const DEFAULT_ROOM_TYPES: RoomType[] = [
  {
    id: 1,
    code: 'STANDARD_CITY',
    name: 'Standard City View',
    description: 'Phòng tiêu chuẩn hướng thành phố, không gian ấm cúng tiện nghi đầy đủ cho 2 khách.',
    maxOccupancy: 2,
    basePricePerNight: 900000,
  },
  {
    id: 2,
    code: 'DELUXE_OCEAN',
    name: 'Deluxe Ocean View',
    description: 'Phòng cao cấp ban công ngắm toàn cảnh biển, trang bị giường King sang trọng.',
    maxOccupancy: 2,
    basePricePerNight: 1500000,
  },
  {
    id: 3,
    code: 'SUITE_PRESIDENT',
    name: 'Presidential Suite',
    description: 'Căn hộ Suite thượng hạng phòng khách riêng biệt, bồn tắm jacuzzi và dịch vụ quản gia.',
    maxOccupancy: 4,
    basePricePerNight: 3500000,
  },
]

export function formatVnd(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(amount)
}

export function formatDateTime(isoString?: string | null): string {
  if (!isoString) return '—'
  return new Intl.DateTimeFormat('vi-VN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Ho_Chi_Minh',
  }).format(new Date(isoString))
}

export function formatDate(isoString?: string | null): string {
  if (!isoString) return '—'
  return new Intl.DateTimeFormat('vi-VN', {
    dateStyle: 'medium',
    timeZone: 'Asia/Ho_Chi_Minh',
  }).format(new Date(isoString))
}
