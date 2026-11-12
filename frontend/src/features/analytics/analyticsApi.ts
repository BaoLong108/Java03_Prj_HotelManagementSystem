import { apiRequest } from '../../shared/api/client'

export type AnalyticsFilter = { from: string; to: string; roomTypeId?: number }
export type AnalyticsDay = { date: string; reservations: number; cancellations: number; demoPaymentVolume: number }
export type AnalyticsRoomType = { id: number; name: string; reservations: number; demoPaymentVolume: number }
export type AnalyticsReport = AnalyticsFilter & {
  hotelZone: string
  totals: {
    reservations: number; pendingPayments: number; confirmed: number; completed: number; cancelled: number; expired: number
    cancellations: number; demoSettlements: number; demoPaymentVolume: number; confirmedBookingValue: number
    membershipDiscount: number; voucherDiscount: number; averageDemoBookingValue: number | null; cancellationRate: number | null
  }
  inventory: { bookedNights: number; estimatedAvailableNights: number; bookedUtilization: number | null }
  days: AnalyticsDay[]
  roomTypes: AnalyticsRoomType[]
  membershipDistribution: Record<string, number>
  comparison: { from: string; to: string; demoPaymentVolume: number } | null
}

const path = '/api/v1/admin/analytics'
function params(filter: AnalyticsFilter) {
  const query = new URLSearchParams({ from: filter.from, to: filter.to })
  if (filter.roomTypeId) query.set('roomTypeId', String(filter.roomTypeId))
  return query
}

export const analyticsApi = {
  report: (filter: AnalyticsFilter, signal?: AbortSignal) => apiRequest<AnalyticsReport>(`${path}?${params(filter)}`, { signal }),
  roomTypes: (signal?: AbortSignal) => apiRequest<AnalyticsRoomType[]>(`${path}/room-types`, { signal }),
  export: (filter: AnalyticsFilter) => apiRequest<Blob>(`${path}/export.csv?${params(filter)}`, { responseType: 'blob' }),
}
