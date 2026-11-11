import { Badge } from '../../shared/ui'
import type { RequestStatus } from './checkInApi'

export const requestLabels: Record<RequestStatus, string> = {
  PENDING: 'Chờ xử lý', APPROVED: 'Đã duyệt yêu cầu', REJECTED: 'Đã từ chối', CANCELLED: 'Đã rút yêu cầu', INVALIDATED: 'Không còn hiệu lực',
}

const tones = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', CANCELLED: 'neutral', INVALIDATED: 'neutral' } as const

export function CheckInStatus({ status }: { status: RequestStatus }) {
  return <Badge tone={tones[status]}>{requestLabels[status]}</Badge>
}
