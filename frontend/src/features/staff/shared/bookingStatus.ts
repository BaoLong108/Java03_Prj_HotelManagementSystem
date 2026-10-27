export const bookingStatuses = ['PENDING_PAYMENT', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'EXPIRED'] as const
export type BookingStatus = typeof bookingStatuses[number]
export const statusLabels: Record<BookingStatus, string> = {
  PENDING_PAYMENT: 'Đang giữ chỗ', CONFIRMED: 'Đã xác nhận', COMPLETED: 'Hoàn tất',
  CANCELLED: 'Đã hủy', EXPIRED: 'Hết hạn',
}

export function isBookingStatus(value: unknown): value is BookingStatus {
  return typeof value === 'string' && bookingStatuses.some(status => status === value)
}
