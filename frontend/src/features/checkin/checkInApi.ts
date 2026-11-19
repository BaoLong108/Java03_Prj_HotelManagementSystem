import { apiRequest } from '../../shared/api/client'
import type { BookingResponse, BookingStatus } from '../booking/types'

export type RequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'INVALIDATED'
export type CheckInRequest = {
  id: number; estimatedArrivalAt: string; earlyCheckInRequested: boolean; arrivalNote: string | null
  status: RequestStatus; reviewedBy?: number | null; reviewedAt: string | null; rejectionReason: string | null
  createdAt: string; updatedAt: string
}
export type Stage = 'CREATED' | 'PAYMENT_PENDING' | 'PAYMENT_SUCCEEDED' | 'CONFIRMED' | 'CHECK_IN_REQUESTED' |
  'CHECK_IN_APPROVED' | 'CHECK_IN_REJECTED' | 'CHECK_IN_CANCELLED' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED'
export type BookingTimeline = {
  booking: BookingResponse
  room: { name: string; coverImageUrl: string | null }
  nights: number
  payment: { status: 'PENDING' | 'SUCCEEDED' | 'FAILED'; provider: string; amount: number; succeededAt: string | null } | null
  confirmationPdfAvailable: boolean
  eligibility: { allowed: boolean; reason: string | null; opensAt: string; closesAt: string }
  requests: CheckInRequest[]
  milestones: { stage: Stage; at: string }[]
}
export type QueueItem = {
  bookingCode: string; bookingStatus: BookingStatus; roomTypeName: string; startAt: string; endAt: string; guestCount: number
  request: CheckInRequest
}
type QueuePage = { items: QueueItem[]; page: number; hasNext: boolean }
type Submission = { estimatedArrival: string; earlyCheckInRequested: boolean; arrivalNote?: string }
export type Decision = 'APPROVED' | 'REJECTED'

const booking = (code: string) => `/api/v1/bookings/${encodeURIComponent(code)}`
const staff = '/api/v1/staff/check-in-requests'

export const checkInApi = {
  timeline: (code: string, signal?: AbortSignal) => apiRequest<BookingTimeline>(`${booking(code)}/timeline`, { signal }),
  submit: (code: string, body: Submission) => apiRequest<CheckInRequest>(`${booking(code)}/check-in-requests`, { method: 'POST', body }),
  cancel: (code: string, id: number) => apiRequest<CheckInRequest>(`${booking(code)}/check-in-requests/${id}/cancel`, { method: 'POST' }),
  list: (status: RequestStatus | '', code: string, page: number, signal?: AbortSignal) => {
    const query = new URLSearchParams({ code, page: String(page) })
    if (status) query.set('status', status)
    return apiRequest<QueuePage>(`${staff}?${query}`, { signal })
  },
  detail: (id: number, signal?: AbortSignal) => apiRequest<QueueItem>(`${staff}/${id}`, { signal }),
  review: (id: number, decision: Decision, rejectionReason?: string) =>
    apiRequest<QueueItem>(`${staff}/${id}/review`, { method: 'POST', body: { decision, rejectionReason } }),
}
