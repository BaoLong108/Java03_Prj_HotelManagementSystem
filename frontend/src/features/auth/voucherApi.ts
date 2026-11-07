import { apiRequest } from '../../shared/api/client'

export type VoucherOffer = { pointsCost: number; discountAmount: number }
export type Voucher = VoucherOffer & {
  code: string
  status: 'AVAILABLE' | 'RESERVED' | 'USED'
  bookingId: number | null
  createdAt: string
  usedAt: string | null
}
export type VoucherPage = { content: Voucher[]; page: number; totalPages: number; totalElements: number }
export const voucherApi = {
  offers: (signal?: AbortSignal) => apiRequest<VoucherOffer[]>('/api/v1/customers/me/vouchers/offers', { signal }),
  wallet: (page: number, signal?: AbortSignal) => apiRequest<VoucherPage>(`/api/v1/customers/me/vouchers?page=${page}`, { signal }),
  redeem: (pointsCost: number, idempotencyKey: string, signal?: AbortSignal) =>
    apiRequest<Voucher>('/api/v1/customers/me/vouchers/redeem', { method: 'POST', body: { pointsCost, idempotencyKey }, signal }),
}
