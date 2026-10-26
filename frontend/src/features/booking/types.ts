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

export function getTodayDateString(): string {
  const now = new Date()
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Ho_Chi_Minh',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
  return formatter.format(now)
}

export function getTomorrowDateString(baseDateStr?: string): string {
  const base = baseDateStr ? new Date(`${baseDateStr}T00:00:00+07:00`) : new Date()
  base.setDate(base.getDate() + 1)
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Ho_Chi_Minh',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
  return formatter.format(base)
}

export function toCheckInIso(dateStr: string): string {
  return `${dateStr}T12:00:00+07:00`
}

export function toCheckOutIso(dateStr: string): string {
  return `${dateStr}T10:00:00+07:00`
}
