import { apiRequest } from '../../shared/api/client'
import type { RoomTypeResponse } from './api'

export type PhysicalRoom = { id: number; roomTypeId: number; roomTypeName: string; roomTypeCode: string; roomNumber: string; floorNumber: number | null; active: boolean }
export const adminRoomApi = {
  types: (signal?: AbortSignal) => apiRequest<RoomTypeResponse[]>('/api/v1/admin/rooms/types', { signal }),
  physical: (typeId: number, signal?: AbortSignal) => apiRequest<PhysicalRoom[]>(`/api/v1/admin/rooms/physical?roomTypeId=${typeId}`, { signal }),
  create: (room: { roomTypeId: number; roomNumber: string; floorNumber: number | null }, images: File[], coverIndex: number, replaceGallery: boolean) => {
    // Reuse an existing RoomType gallery without replacing its bytes or cover.
    if (images.length === 0) return apiRequest<PhysicalRoom>('/api/v1/admin/rooms/physical', { method: 'POST', body: room })
    const body = new FormData()
    body.append('room', new Blob([JSON.stringify(room)], { type: 'application/json' }))
    images.forEach((image) => body.append('images', image))
    body.append('coverIndex', String(coverIndex))
    body.append('replaceGallery', String(replaceGallery))
    return apiRequest<PhysicalRoom>('/api/v1/admin/rooms/physical', { method: 'POST', body })
  },
  price: (type: RoomTypeResponse, basePricePerNight: number) => apiRequest<RoomTypeResponse>(`/api/v1/admin/rooms/types/${type.id}`, {
    method: 'PUT', body: { code: type.code, name: type.name, description: type.description, maxOccupancy: type.maxOccupancy, basePricePerNight },
  }),
}
