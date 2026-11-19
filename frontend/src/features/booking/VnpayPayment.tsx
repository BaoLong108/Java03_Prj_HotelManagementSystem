import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { apiRequest, errorMessage } from '../../shared/api/client'
import { Alert, Button, Card, LinkButton, LoadingState, PageHeader } from '../../shared/ui'
import { BookingStatusBadge } from './BookingComponents'
import { formatVnd } from './types'
import type { BookingStatus } from './types'
import { bookingApi } from './api'
import './bookingReview.css'

export type PaymentState = { bookingCode: string; bookingStatus: BookingStatus; paymentStatus: string;
  provider: string | null; outcome: string | null; responseCode: string | null; holdExpiresAt: string; payableAmount: number }
export const paymentApi = {
  availability: (signal?: AbortSignal) => apiRequest<{ available: boolean }>('/api/v1/payments/vnpay/availability', { signal }),
  checkout: (bookingCode: string, idempotencyKey: string) => apiRequest<{ paymentUrl: string }>('/api/v1/payments/vnpay/checkout', { method: 'POST', body: { bookingCode, idempotencyKey } }),
  status: (query: string, signal?: AbortSignal) => apiRequest<PaymentState>(`/api/v1/payments/vnpay/status?${query}`, { signal }),
}
export const paymentNavigation = {
  redirect(url: string) {
    const target = new URL(url)
    if (target.protocol !== 'https:' || target.hostname !== 'sandbox.vnpayment.vn' || target.username || target.password
      || (target.port && target.port !== '443') || target.pathname !== '/paymentv2/vpcpay.html') throw Error('Địa chỉ thanh toán không hợp lệ.')
    window.location.assign(target.href)
  },
}

export function VnpayPayment({ bookingCode, disabled, onBusyChange }: { bookingCode: string; disabled: boolean; onBusyChange?: (busy: boolean) => void }) {
  const [available, setAvailable] = useState<boolean | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [requestKey] = useState(() => `web-vnpay-${crypto.randomUUID()}`)
  useEffect(() => {
    const controller = new AbortController(); setAvailable(null); setError('')
    paymentApi.availability(controller.signal).then(result => { if (!controller.signal.aborted) setAvailable(result.available) })
      .catch(reason => { if (!controller.signal.aborted) { setAvailable(false); setError(errorMessage(reason)) } })
    return () => controller.abort()
  }, [bookingCode])
  async function checkout() {
    if (busy || disabled || !available) return
    setBusy(true); setError(''); onBusyChange?.(true)
    try { const result = await paymentApi.checkout(bookingCode, requestKey); paymentNavigation.redirect(result.paymentUrl) }
    catch (reason) { setError(errorMessage(reason)); setBusy(false); onBusyChange?.(false) }
  }
  if (available === null) return <LoadingState message="Đang kiểm tra phương thức thanh toán…" />
  return <div className="vnpay-checkout">{error && <Alert>{error}</Alert>}
    {!available ? <Alert tone="warning">Thanh toán VNPay hiện chưa sẵn sàng. Booking vẫn đang được giữ đến thời hạn hiển thị; bạn chưa bị trừ tiền.</Alert>
      : <><Button onClick={checkout} busy={busy} disabled={disabled}>Thanh toán qua VNPay Sandbox</Button><p className="small muted">Bạn sẽ chuyển đến trang thanh toán VNPay Sandbox. Đặt phòng chỉ được xác nhận sau khi máy chủ xác minh giao dịch.</p></>}
  </div>
}

export function PaymentReturnPage() {
  const [params] = useSearchParams()
  const code = params.get('bookingCode') ?? /^Booking (BK[0-9A-Z-]+)$/.exec(params.get('vnp_OrderInfo') ?? '')?.[1]
  const reference = params.get('vnp_TxnRef')
  const identifiers = new URLSearchParams()
  if (code) identifiers.set('bookingCode', code)
  if (reference) identifiers.set('reference', reference)
  const query = identifiers.toString()
  const [state, setState] = useState<{ query: string; data?: PaymentState; error?: string }>({ query: '' })
  const [retry, setRetry] = useState(0)
  const [checking, setChecking] = useState(false)
  const [pdfError, setPdfError] = useState('')
  useEffect(() => {
    if (!query) return
    const controller = new AbortController()
    let timer: number | undefined; let attempts = 0
    async function refresh() {
      setChecking(true)
      try {
        const data = await paymentApi.status(query, controller.signal)
        if (controller.signal.aborted) return
        setState({ query, data })
        if (++attempts < 40 && data.bookingStatus === 'PENDING_PAYMENT' && data.outcome !== 'FAILED'
          && data.outcome !== 'REVIEW_REQUIRED' && Date.parse(data.holdExpiresAt) > Date.now()) {
          timer = window.setTimeout(refresh, 3000)
        }
      } catch (reason) { if (!controller.signal.aborted) setState({ query, error: errorMessage(reason) }) }
      finally { if (!controller.signal.aborted) setChecking(false) }
    }
    void refresh()
    return () => { controller.abort(); window.clearTimeout(timer) }
  }, [query, retry])
  const current = state.query === query ? state : { query }
  const data = current.data
  const paid = data?.paymentStatus === 'SUCCEEDED' && ['CONFIRMED', 'COMPLETED'].includes(data.bookingStatus)
  const expired = data && (data.bookingStatus === 'EXPIRED' || new Date(data.holdExpiresAt).getTime() <= Date.now())
  const review = data?.outcome === 'REVIEW_REQUIRED'
  const failed = data?.outcome === 'FAILED'
  const cancelled = data?.bookingStatus === 'CANCELLED'
  return <><PageHeader title="Kết quả thanh toán" description="Trạng thái được lấy từ hệ thống khách sạn sau khi xác minh giao dịch." /><Card className="booking-payment-result">
    {!query && <Alert>Không tìm thấy thông tin giao dịch. Vui lòng mở kỳ nghỉ của bạn để xem trạng thái.</Alert>}
    {query && !data && !current.error && <LoadingState message="Đang kiểm tra kết quả thanh toán…" />}
    {current.error && <Alert>{current.error}</Alert>}
    {data && <><BookingStatusBadge status={data.bookingStatus} /><h2>{review ? 'Giao dịch cần đối soát' : paid ? 'Đặt phòng đã được xác nhận' : cancelled ? 'Đặt phòng đã hủy' : failed ? (data.responseCode === '24' ? 'Bạn đã hủy thanh toán' : 'Thanh toán chưa thành công') : expired ? 'Đã hết thời gian giữ chỗ' : 'Đang chờ xác nhận thanh toán'}</h2>
      <p>Mã đặt phòng <strong>{data.bookingCode}</strong> · {formatVnd(data.payableAmount)}</p>
      <p>{review ? 'Khách sạn đã ghi nhận kết quả cổng thanh toán nhưng chưa thể xác nhận phòng. Vui lòng liên hệ khách sạn để đối soát; chưa có xác nhận hoàn tiền.'
        : paid ? (data.provider === 'ZERO_PAYABLE' ? 'Booking đã được tất toán 0₫, không phát sinh giao dịch ngân hàng.' : 'Thanh toán đã được máy chủ xác minh. Bạn có thể xem kỳ nghỉ và tải PDF xác nhận.')
        : cancelled ? 'Booking này đã hủy. Nếu cổng đã trừ tiền, hãy liên hệ khách sạn để đối soát.'
        : failed ? 'Booking chưa được xác nhận. Bạn có thể thử lại nếu vẫn còn thời gian giữ chỗ.'
        : expired ? 'Không thanh toán lại đơn này. Nếu cổng đã trừ tiền, hãy liên hệ khách sạn để kiểm tra giao dịch.'
        : 'Đang chờ thông báo từ VNPay. Trang tự cập nhật; việc quay lại website không tự xác nhận thanh toán.'}</p>
      {failed && !expired && data.bookingStatus === 'PENDING_PAYMENT' && <VnpayPayment key={data.bookingCode} bookingCode={data.bookingCode} disabled={checking} />}
    </>}
    {pdfError && <Alert>{pdfError}</Alert>}
    <div className="button-row">{paid && data && <Button onClick={() => {
      setPdfError('')
      void bookingApi.downloadConfirmation(data.bookingCode).catch(error => setPdfError(errorMessage(error)))
    }}>Tải PDF xác nhận</Button>}
      {query && !paid && <Button variant="secondary" busy={checking} onClick={() => setRetry(value => value + 1)}>Cập nhật trạng thái</Button>}
      <LinkButton to={data ? `/my-bookings/${encodeURIComponent(data.bookingCode)}` : '/my-bookings'}>Xem kỳ nghỉ của tôi</LinkButton><Link to="/rooms">Tìm phòng khác</Link></div>
  </Card></>
}
