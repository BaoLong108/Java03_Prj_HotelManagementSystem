import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Alert, Badge, Card, PriceDisplay } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { formatHoldTime, holdSecondsRemaining } from './hold'

export type BookingStatus = 'PENDING_PAYMENT' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED'
const bookingLabels: Record<BookingStatus, string> = { PENDING_PAYMENT: 'Chờ thanh toán', CONFIRMED: 'Đã xác nhận', COMPLETED: 'Đã hoàn tất', CANCELLED: 'Đã hủy', EXPIRED: 'Hết hạn' }
export function BookingStatusBadge({ status }: { status: BookingStatus }) {
  return <Badge tone={status === 'CONFIRMED' || status === 'COMPLETED' ? 'success' : status === 'PENDING_PAYMENT' ? 'warning' : status === 'CANCELLED' ? 'danger' : 'neutral'}>{bookingLabels[status] ?? 'Chưa xác định'}</Badge>
}

// Parent must refetch booking status and prevent payment at/after expiry. This UI never changes server state.
export function HoldCountdown({ bookingId, holdExpiresAt, onElapsed }: { bookingId: number; holdExpiresAt: string; onElapsed: () => void }) {
  const [now, setNow] = useState(Date.now)
  const handled = useRef('')
  const seconds = holdSecondsRemaining(holdExpiresAt, now)
  useEffect(() => {
    const tick = () => setNow(Date.now())
    tick()
    const timer = window.setInterval(tick, 1000)
    document.addEventListener('visibilitychange', tick)
    return () => { window.clearInterval(timer); document.removeEventListener('visibilitychange', tick) }
  }, [bookingId, holdExpiresAt])
  useEffect(() => {
    const key = `${bookingId}:${holdExpiresAt}`
    if (seconds === 0 && handled.current !== key) { handled.current = key; onElapsed() }
  }, [seconds, bookingId, holdExpiresAt, onElapsed])
  if (seconds === null) return <Alert tone="warning">Chưa xác định được thời hạn giữ chỗ. Vui lòng xác thực lại đặt phòng trước khi thanh toán.</Alert>
  if (seconds === 0) return <Alert tone="warning">Thời gian giữ chỗ đã kết thúc. Cần kiểm tra lại trạng thái đặt phòng với máy chủ.</Alert>
  return <div className="hold-countdown"><Icon name="clock" /><div><strong>Thời gian giữ chỗ còn lại</strong><p>Hoàn tất thanh toán trong thời gian được giữ phòng.</p></div><span role="timer" aria-label={`Còn ${Math.floor(seconds / 60)} phút ${seconds % 60} giây`}>{formatHoldTime(seconds)}</span></div>
}

// All amounts are supplied by the pricing/booking API. No total is calculated here.
export function BookingPriceSummary({ baseAmount, discountRate, payableAmount }: { baseAmount: number; discountRate: number; payableAmount: number }) {
  return <dl className="details-list"><div><dt>Giá phòng gốc</dt><dd><PriceDisplay amount={baseAmount} /></dd></div><div><dt>Ưu đãi thành viên</dt><dd>{new Intl.NumberFormat('vi-VN', { style: 'percent', maximumFractionDigits: 2 }).format(discountRate)}</dd></div><div className="summary-total"><dt>Cần thanh toán</dt><dd><PriceDisplay amount={payableAmount} /></dd></div></dl>
}

export function BookingCard({ code, roomTypeName, stayLabel, status, paymentLabel, amountPaid, action }: {
  code: string; roomTypeName: string; stayLabel: string; status: BookingStatus; paymentLabel: string; amountPaid: number | null; action?: ReactNode
}) {
  return <Card className="booking-history-card"><div className="card-heading"><div><p className="eyebrow">{code}</p><h2>{roomTypeName}</h2></div><BookingStatusBadge status={status} /></div><p className="muted"><Icon name="calendar" size={16} /> {stayLabel}</p><dl className="details-list"><div><dt>Thanh toán</dt><dd>{paymentLabel}</dd></div><div><dt>Đã trả</dt><dd><PriceDisplay amount={amountPaid} /></dd></div></dl>{action}</Card>
}
