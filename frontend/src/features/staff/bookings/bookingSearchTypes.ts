import type { BookingStatus } from '../shared/bookingStatus'

export type SearchMode = 'bookingCode' | 'phone'
export type BookingQuery = { bookingCode: string; phone?: never } | { phone: string; bookingCode?: never }
export type BookingSearchItem = {
  bookingId: number
  bookingCode: string
  status: BookingStatus
  roomTypeId?: number
  roomTypeName?: string
  roomNumber?: string | null
  startAt: string
  endAt: string
  guestCount: number
  holdExpiresAt?: string
  baseAmount: string
  discountRate: string
  payableAmount: string
  customer?: { phoneMasked: string; emailMasked: string }
  clientMaskedContact?: boolean
}

/** Adapted view model: the current real BookingResponse has no room/contact metadata. */
export type BookingSearchResult = { items: BookingSearchItem[] }
