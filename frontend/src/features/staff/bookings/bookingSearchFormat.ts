import { hotelDate } from '../shared/staffTime'
import type { BookingQuery, BookingSearchItem, SearchMode } from './bookingSearchTypes'

export function bookingQuery(mode: SearchMode, input: string): BookingQuery {
  const value = input.trim()
  if (!value) throw new Error(mode === 'phone' ? 'Nhập số điện thoại cần tìm.' : 'Nhập mã booking cần tìm.')
  if (mode === 'bookingCode') {
    if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(value)) throw new Error('Mã booking chỉ gồm chữ, số, dấu gạch ngang hoặc gạch dưới (tối đa 64 ký tự).')
    return { bookingCode: value }
  }
  const phone = value.replace(/[\s()-]/g, '')
  if (!/^\+?\d{7,15}$/.test(phone)) throw new Error('Số điện thoại cần có 7–15 chữ số, có thể bắt đầu bằng +.')
  return { phone }
}

/** Format the server snapshot exactly; never recalculate a price or discount. */
export function formatVnd(value: string): string {
  if (!/^\d{1,15}(\.\d{1,2})?$/.test(value)) return 'Chưa có số tiền'
  const [integer, fraction = ''] = value.split('.')
  const decimals = fraction.replace(/0+$/, '')
  return `${new Intl.NumberFormat('vi-VN').format(BigInt(integer))}${decimals ? ',' + decimals : ''} ₫`
}

export function bookingCalendarLink(item: BookingSearchItem): string {
  const params = new URLSearchParams({ from: hotelDate(new Date(item.startAt)) })
  if (item.roomTypeId) params.set('roomTypeId', String(item.roomTypeId))
  return `/staff/calendar?${params}`
}
