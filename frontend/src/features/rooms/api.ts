import { apiRequest } from '../../shared/api/client'

export type RoomImage = { id: number; url: string; cover: boolean; sortOrder: number; width: number; height: number }
export type RoomTypeResponse = {
  id: number
  code: string
  name: string
  description: string | null
  maxOccupancy: number
  basePricePerNight: number
  activeRoomCount: number
  active: boolean
  coverImageUrl: string | null
  images: RoomImage[]
}

export const roomApi = {
  list: (signal?: AbortSignal) =>
    apiRequest<RoomTypeResponse[]>('/api/v1/rooms', { auth: false, signal }),
  get: (id: number, signal?: AbortSignal) =>
    apiRequest<RoomTypeResponse>(`/api/v1/rooms/${id}`, { auth: false, signal }),
}
