import { apiRequest } from '../../shared/api/client'

export type RoomTypeResponse = {
  id: number
  code: string
  name: string
  description: string | null
  maxOccupancy: number
  basePricePerNight: number
  activeRoomCount: number
}

export const roomApi = {
  list: (signal?: AbortSignal) =>
    apiRequest<RoomTypeResponse[]>('/api/v1/rooms', { auth: false, signal }),
  get: (id: number, signal?: AbortSignal) =>
    apiRequest<RoomTypeResponse>(`/api/v1/rooms/${id}`, { auth: false, signal }),
}
