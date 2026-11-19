import { apiRequest } from '../../shared/api/client'
import type { MembershipSummary, MembershipTier } from '../auth/membershipApi'
import type { BookingStatus } from '../booking/types'
import type { TransactionDetail, TransactionStatus } from '../transactions/api'

export const crmPath = '/api/v1/admin/customers'
export type Page<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type Customer = {
  id: number; fullName: string; email: string; phone: string | null; active: boolean; registeredAt: string
  tier: MembershipTier; points: number; eligibleSpending: number; bookingCount: number
}
export type Directory = {
  statistics: { total: number; standard: number; vip: number; diamond: number }
  customers: Page<Customer>
}
export type Profile = {
  customer: Customer; membership: MembershipSummary; completedBookings: number
  verifiedPayments: number; pointsEarned: number; pointsRedeemed: number
}
export type Booking = {
  bookingCode: string; roomType: string; startAt: string; endAt: string; guests: number; createdAt: string
  status: BookingStatus; paymentStatus: TransactionStatus | null; baseAmount: number
  membershipDiscount: number; voucherDiscount: number; payableAmount: number
}
export type Point = { id: number; type: 'EARNED' | 'REDEEMED'; points: number; at: string; bookingCode: string | null; voucherCode: string | null }
export type Voucher = {
  id: number; code: string; points: number; discount: number; createdAt: string
  status: 'USED' | 'RESERVED' | 'AVAILABLE'; bookingCode: string | null; usedAt: string | null
}
export type CheckIn = {
  id: number; bookingCode: string; createdAt: string; estimatedArrival: string; early: boolean
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'; reviewer: string | null; reviewedAt: string | null; rejectionReason: string | null
}
export type CrmTransaction = TransactionDetail

export function crmRead<T>(path: string, signal: AbortSignal) {
  return apiRequest<T>(path, { signal })
}
