import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { errorMessage } from '../../shared/api/client'
import { Alert, Button, Field } from '../../shared/ui'
import { bookingApi } from './api'
import type { BookingResponse } from './types'
import { formatVnd } from './types'
import '../auth/vouchers.css'

export function VoucherInput({ booking, disabled, onChange, onBusyChange }: {
  booking: BookingResponse; disabled: boolean; onChange: (booking: BookingResponse) => void; onBusyChange: (busy: boolean) => void
}) {
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const lifetime = useRef<AbortController | null>(null)
  const inFlight = useRef(false)
  useEffect(() => {
    const controller = new AbortController()
    lifetime.current = controller
    return () => controller.abort()
  }, [])

  async function update(remove = false) {
    if (inFlight.current || disabled || lifetime.current?.signal.aborted) return
    inFlight.current = true; setBusy(true); setError(''); onBusyChange(true)
    const signal = lifetime.current?.signal
    try {
      const latest = remove ? await bookingApi.removeVoucher(booking.bookingCode, signal)
        : await bookingApi.applyVoucher(booking.bookingCode, code, signal)
      if (!signal?.aborted) { onChange(latest); setCode('') }
    } catch (failure) {
      if (!signal?.aborted) setError(errorMessage(failure))
    } finally {
      inFlight.current = false
      if (!signal?.aborted) { setBusy(false); onBusyChange(false) }
    }
  }

  return <section className="checkout-voucher" aria-label="Mã giảm giá">
    <h3>Mã giảm giá</h3>
    <p>Giảm tiếp sau ưu đãi hạng thành viên. Một mã cho mỗi booking; số phải trả thấp nhất là 0₫.</p>
    {booking.voucherCode ? <div className="voucher-applied"><strong className="voucher-code">{booking.voucherCode}</strong>
      <span>Đã giảm {formatVnd(booking.voucherDiscountAmount ?? 0)}</span>
      <Button variant="secondary" busy={busy} disabled={disabled} onClick={() => void update(true)}>Gỡ mã</Button>
    </div> : <form onSubmit={event => { event.preventDefault(); if (code.length === 6) void update() }}>
      <Field label="Nhập mã giảm giá" value={code} maxLength={6} autoComplete="off" placeholder="VD: A1B2C3"
        disabled={disabled || busy} onChange={event => setCode(event.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6))} />
      <Button type="submit" variant="secondary" busy={busy} disabled={disabled || code.length !== 6}>Áp dụng mã</Button>
    </form>}
    <Link to="/account">Xem mã của tôi / Đổi điểm lấy mã</Link>
    {error && <Alert>{error}</Alert>}
  </section>
}
