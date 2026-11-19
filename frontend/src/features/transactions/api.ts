import { apiRequest } from '../../shared/api/client'
import type { BookingStatus } from '../booking/types'

const path = '/api/v1/customers/me/transactions'

export const transactionStatusLabels = {
  SUCCEEDED: 'Thanh toán thành công',
  PENDING: 'Chờ thanh toán',
  PROCESSING: 'Đang chờ xác minh thanh toán',
  FAILED: 'Thanh toán thất bại',
  CANCELLED: 'Đã hủy',
  EXPIRED: 'Hết hạn',
  REVIEW_REQUIRED: 'Cần đối soát',
} as const

export const providerLabels = { VNPAY: 'VNPay Sandbox', ZERO_PAYABLE: 'Tất toán 0₫', DEMO: 'Thanh toán thử' } as const
export type TransactionStatus = keyof typeof transactionStatusLabels
export type TransactionProvider = keyof typeof providerLabels

export type Transaction = {
  id: number
  bookingCode: string
  roomTypeName: string
  coverImageUrl: string | null
  reference: string
  provider: TransactionProvider
  method: TransactionProvider | 'VOUCHER'
  amount: number
  currency: 'VND'
  initiatedAt: string
  settledAt?: string | null
  status: TransactionStatus
  bookingStatus: BookingStatus
  receiptAvailable: boolean
}

export type TransactionDetail = {
  transaction: Transaction
  providerTransactionId: string | null
  baseAmount: number
  membershipDiscount: number
  voucherDiscount: number
  payableAmount: number
  gatewayVerifiedAt: string | null
  settledAt: string | null
}

export type TransactionPage = { items: Transaction[]; page: number; size: number; totalElements: number; totalPages: number }
export type Filters = {
  search: string
  status: TransactionStatus | ''
  provider: TransactionProvider | ''
  from: string
  to: string
  sort: 'NEWEST' | 'OLDEST'
}

export function transactionQuery(filters: Filters, page: number) {
  const params = new URLSearchParams({ page: String(page), size: '10', sort: filters.sort })
  for (const key of ['search', 'status', 'provider', 'from', 'to'] as const) {
    if (filters[key]) params.set(key, filters[key])
  }
  return params.toString()
}

export const transactionApi = {
  list: (query: string, signal: AbortSignal) => apiRequest<TransactionPage>(`${path}?${query}`, { signal }),
  detail: (id: number, signal: AbortSignal) => apiRequest<TransactionDetail>(`${path}/${id}`, { signal }),
}
