import { apiRequest, ApiError, errorMessage } from '../../../shared/api/client'
import { isHotelDate } from '../shared/staffTime'

export type BlockDraft = { physicalRoomId: string; start: string; end: string; reason: string }
export type RoomBlock = { id: number; physicalRoomId: number; roomNumber: string; startAt: string; endAt: string; reason: string; active: boolean; createdByUserId: number; createdAt: string; releasedByUserId: number | null; releasedAt: string | null }
export function localInstant(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T([01]\d|2[0-3]):[0-5]\d$/.test(value) || !isHotelDate(value.slice(0, 10))) return null
  return `${value}:00+07:00`
}
export function validateBlock(draft: BlockDraft, now = Date.now()): Record<string, string> {
  const errors: Record<string, string> = {}
  const start = localInstant(draft.start), end = localInstant(draft.end)
  if (!/^[1-9]\d*$/.test(draft.physicalRoomId) || !Number.isSafeInteger(Number(draft.physicalRoomId))) errors.physicalRoomId = 'Chọn phòng vật lý.'
  if (!draft.reason.trim()) errors.reason = 'Nhập lý do chặn phòng.'
  if (!start) errors.startAt = 'Ngày giờ bắt đầu không hợp lệ.'
  else if (Date.parse(start) <= now) errors.startAt = 'Bắt đầu phải ở tương lai.'
  if (!end) errors.endAt = 'Ngày giờ kết thúc không hợp lệ.'
  else if (start && Date.parse(end) <= Date.parse(start)) errors.endAt = 'Kết thúc phải sau bắt đầu.'
  return errors
}
export function blockError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'BOOKING_OVERLAP') return 'Phòng đã có booking hoặc giữ chỗ trong khoảng này'
    if (error.code === 'BLOCK_OVERLAP') return 'Phòng đã bị chặn trong khoảng này'
    if (error.code === 'BLOCK_ALREADY_RELEASED') return 'Khoảng chặn đã được mở. Hãy tải lại dữ liệu.'
  }
  return errorMessage(error)
}
export function createBlock(draft: BlockDraft) {
  return apiRequest<RoomBlock>('/api/v1/admin/room-blocks', { method: 'POST', body: {
    physicalRoomId: Number(draft.physicalRoomId), startAt: localInstant(draft.start), endAt: localInstant(draft.end), reason: draft.reason.trim(),
  } })
}
export const releaseBlock = (id: number) => apiRequest<RoomBlock>(`/api/v1/admin/room-blocks/${id}`, { method: 'DELETE' })
export const listBlocks = (id: number, signal?: AbortSignal) => apiRequest<RoomBlock[]>(`/api/v1/admin/room-blocks?roomId=${id}&history=false`, { signal })
