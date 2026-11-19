import { apiRequest } from '../../shared/api/client'
import type { RoomTypeResponse } from './api'

export type PhysicalRoom = { id: number; roomTypeId: number; roomTypeName: string; roomTypeCode: string; roomNumber: string; floorNumber: number | null; active: boolean }
type NewRoomType = Pick<RoomTypeResponse, 'code' | 'name' | 'description' | 'maxOccupancy' | 'basePricePerNight' | 'amenities'>
export const adminRoomApi = {
  gallery: (typeId: number, gallery: { items: { imageId?: number; uploadIndex?: number }[]; coverIndex: number; expectedRevision: string }, files: File[]) => {
    const body = new FormData()
    body.append('gallery', new Blob([JSON.stringify(gallery)], { type: 'application/json' }))
    files.forEach(file => body.append('images', file))
    return apiRequest<RoomTypeResponse['images']>(`/api/v1/admin/rooms/types/${typeId}/images`, { method: 'PUT', body })
  },
  amenities: (type: RoomTypeResponse, amenities: string[]) => apiRequest<RoomTypeResponse>(`/api/v1/admin/rooms/types/${type.id}`, {
    method: 'PUT', body: { code: type.code, name: type.name, description: type.description, maxOccupancy: type.maxOccupancy, basePricePerNight: type.basePricePerNight, amenities },
  }),
  types: (signal?: AbortSignal) => apiRequest<RoomTypeResponse[]>('/api/v1/admin/rooms/types', { signal }),
  createType: (body: NewRoomType) => apiRequest<RoomTypeResponse>('/api/v1/admin/rooms/types', { method: 'POST', body }),
  physical: (typeId: number, signal?: AbortSignal) => apiRequest<PhysicalRoom[]>(`/api/v1/admin/rooms/physical?roomTypeId=${typeId}`, { signal }),
  update: (id: number, roomNumber: string, floorNumber: number | null) => apiRequest<PhysicalRoom>(`/api/v1/admin/rooms/physical/${id}`, {
    method: 'PUT', body: { roomNumber, floorNumber },
  }),
  hide: (id: number) => apiRequest<void>(`/api/v1/admin/rooms/physical/${id}`, { method: 'DELETE' }),
  create: (body: { roomTypeId: number; roomNumber: string; floorNumber: number; active: boolean }) =>
    apiRequest<PhysicalRoom>('/api/v1/admin/rooms/physical', { method: 'POST', body }),
  price: (type: RoomTypeResponse, basePricePerNight: number) => apiRequest<RoomTypeResponse>(`/api/v1/admin/rooms/types/${type.id}`, {
    method: 'PUT', body: { code: type.code, name: type.name, description: type.description, maxOccupancy: type.maxOccupancy, basePricePerNight, amenities: type.amenities },
  }),
}
