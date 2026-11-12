import { apiRequest } from '../../../shared/api/client'
import type { BookingStatus } from '../../booking/types'

export type FloorStatus = 'AVAILABLE' | 'ASSIGNED' | 'HELD' | 'BLOCKED' | 'OUT_OF_SERVICE'
export type FloorRoom = {
  roomId: number
  roomNumber: string
  roomTypeId: number
  roomTypeName: string
  operationalStatus: FloorStatus
  block: { id: number; startAt: string; endAt: string; reason: string } | null
  assignment: { bookingCode: string; status: BookingStatus; startAt: string; endAt: string } | null
  updatedAt: string
}
export type FloorOverview = { asOf: string; floors: { floorNumber: number | null; rooms: FloorRoom[] }[] }

export const floorApi = {
  overview: (signal?: AbortSignal) => apiRequest<FloorOverview>('/api/v1/staff/floor-overview', { signal }),
}
