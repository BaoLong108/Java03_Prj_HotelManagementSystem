import { apiRequest } from '../../shared/api/client'

export type MembershipTier = 'STANDARD' | 'VIP' | 'DIAMOND'
export type MembershipSummary = {
  tierCode: MembershipTier
  eligibleSpendTotal: number
  pointsBalance: number
  discountRate: number
  nextTier: MembershipTier | null
  nextTierSpend: number | null
  remainingSpend: number
}
export type LoyaltyAward = {
  bookingCode: string
  eligibleAmount: number
  pointsAwarded: number
  awardedAt: string
}
export type LoyaltyHistory = {
  content: LoyaltyAward[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export const membershipApi = {
  summary: (signal?: AbortSignal) => apiRequest<MembershipSummary>('/api/v1/customers/me/membership', { signal }),
  history: (page = 0, signal?: AbortSignal) =>
    apiRequest<LoyaltyHistory>(`/api/v1/customers/me/loyalty-awards?page=${page}&size=10`, { signal }),
}
