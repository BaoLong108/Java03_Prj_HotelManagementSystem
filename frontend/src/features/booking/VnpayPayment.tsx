import { useEffect, useState } from 'react'
import { apiRequest, errorMessage } from '../../shared/api/client'
import { Alert, Button } from '../../shared/ui'
import { bookingApi } from './api'
import { Link, useSearchParams } from 'react-router-dom'
import { BookingStatusBadge } from './BookingComponents'
import type { BookingResponse } from './types'

export function VnpayPayment({ bookingCode, disabled }: { bookingCode: string; disabled: boolean }) {
  const [available, setAvailable] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [requestKey] = useState(() => `web-vnpay-${crypto.randomUUID()}`)
  useEffect(() => {
    const controller = new AbortController()
    apiRequest<{ available: boolean }>('/api/v1/payments/vnpay/availability', { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setAvailable(result.available) })
      .catch(() => { if (!controller.signal.aborted) setAvailable(false) })
    return () => controller.abort()
  }, [])
  if (!available) return null
  async function checkout() {
    if (busy || disabled) return
    setBusy(true); setError('')
    try {
      const result = await apiRequest<{ paymentUrl: string }>('/api/v1/payments/vnpay/checkout', {
        method: 'POST', body: { bookingCode, idempotencyKey: requestKey },
      })
      const url = new URL(result.paymentUrl)
      if (url.origin !== 'https://sandbox.vnpayment.vn') throw new Error('URL VNPay Sandbox không hợp lệ.')
      window.location.assign(url.href)
    } catch (error) { setError(errorMessage(error)); setBusy(false) }
  }
  return <>{error && <Alert>{error}</Alert>}<Button variant="secondary" onClick={checkout} busy={busy} disabled={disabled}>
    Thanh toán VNPay Sandbox</Button></>
}

export function PaymentReturnPage() {
  const [params] = useSearchParams()
  const code = /^Booking (BK[0-9A-Z-]+)$/.exec(params.get('vnp_OrderInfo') ?? '')?.[1]
  const [booking, setBooking] = useState<BookingResponse | null>(null)
  const [error, setError] = useState('')
  const [reload, setReload] = useState(0)
  useEffect(() => {
    if (!code) return
    const controller = new AbortController()
    bookingApi.getByCode(code, controller.signal).then(result => { if (!controller.signal.aborted) setBooking(result) })
      .catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)) })
    return () => controller.abort()
  }, [code, reload])
  return <div className="container"><h1>Kết quả thanh toán</h1><p>Trạng thái dưới đây được xác thực từ máy chủ sau khi nhận thông báo VNPay.</p>
    {booking && <BookingStatusBadge status={booking.status} />}{error && <Alert>{error}</Alert>}
    {code && <Button variant="secondary" onClick={() => setReload(n => n + 1)}>Cập nhật trạng thái</Button>}
    <Link className="button button--primary" to={code ? `/my-bookings/${encodeURIComponent(code)}` : '/my-bookings'}>Xem đặt phòng</Link>
  </div>
}
