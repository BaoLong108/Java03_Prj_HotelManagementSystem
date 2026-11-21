import { apiRequest } from '../../../shared/api/client'
import { addHotelDays, hotelDate, isHotelDate } from '../shared/staffTime'
import type { BookingStatus } from '../shared/bookingStatus'

export type Period = { from: string; to: string }
export type DailyMetric = { date: string; revenue: string; soldRoomNights: number; availableRoomNights: number; occupancyRate: number }
export type Summary = Period & Omit<DailyMetric, 'date'> & { revpar: string; bookingCounts: Record<BookingStatus, number>; pendingHolds: number; completedStays: number; daily: DailyMetric[] }
export type BlockHistory = { id: number; roomId: number; roomNumber: string; startAt: string; endAt: string; reason: string; createdByName: string; createdAt: string; releasedByName: string | null; releasedAt: string | null; active: boolean }
export function presetPeriod(preset: '7' | '30' | 'month', today = hotelDate()): Period {
  return { from: preset === 'month' ? `${today.slice(0, 7)}-01` : addHotelDays(today, preset === '7' ? -6 : -29), to: addHotelDays(today, 1) }
}
export function reportQuery(period: Period): URLSearchParams {
  if (!isHotelDate(period.from) || !isHotelDate(period.to)) throw new Error('Nhập ngày hợp lệ.')
  const days = (Date.parse(period.to) - Date.parse(period.from)) / 86400000
  if (days < 1 || days > 92) throw new Error('Kỳ báo cáo từ 1 đến 92 ngày; ngày đến không nằm trong kỳ.')
  return new URLSearchParams(period)
}
export const getSummary = (period: Period, signal?: AbortSignal) => apiRequest<Summary>(`/api/v1/staff/reports/summary?${reportQuery(period)}`, { signal })
export function getHistory(period: Period, roomId: string, signal?: AbortSignal) {
  const query = reportQuery(period)
  if (roomId) {
    if (!/^[1-9]\d*$/.test(roomId) || !Number.isSafeInteger(Number(roomId))) throw new Error('Phòng không hợp lệ.')
    query.set('roomId', roomId)
  }
  return apiRequest<BlockHistory[]>(`/api/v1/staff/reports/room-block-history?${query}`, { signal })
}
export const getCsv = (period: Period) => apiRequest<Blob>(`/api/v1/admin/reports/bookings.csv?${reportQuery(period)}`, { responseType: 'blob' })
