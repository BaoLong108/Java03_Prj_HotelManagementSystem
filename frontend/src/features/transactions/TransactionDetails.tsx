import { useEffect, useState } from 'react'
import { errorMessage } from '../../shared/api/client'
import { Alert, Badge, Button, LinkButton, Skeleton } from '../../shared/ui'
import { bookingApi } from '../booking/api'
import { BookingStatusBadge } from '../booking/BookingComponents'
import { formatDateTime, formatVnd } from '../booking/types'
import { providerLabels, transactionApi, transactionStatusLabels } from './api'
import type { Transaction, TransactionDetail } from './api'

export function PaymentStatusBadge({ status }: Pick<Transaction, 'status'>) {
  const tone = status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'danger'
    : status === 'PENDING' || status === 'PROCESSING' || status === 'REVIEW_REQUIRED' ? 'warning' : 'neutral'
  return <Badge tone={tone}>{transactionStatusLabels[status]}</Badge>
}

export function paymentMethod(transaction: Transaction) {
  return transaction.method === 'VOUCHER' ? 'Voucher · Tất toán 0₫' : providerLabels[transaction.method]
}

export function TransactionDetails({ id }: { id: number }) {
  const [detail, setDetail] = useState<TransactionDetail | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [reload, setReload] = useState(0)
  const [pdfBusy, setPdfBusy] = useState(false)
  const [pdfError, setPdfError] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    transactionApi.detail(id, controller.signal).then(data => {
      if (!controller.signal.aborted) setDetail(data)
    }).catch(() => { if (!controller.signal.aborted) setLoadError(true) })
    return () => controller.abort()
  }, [id, reload])

  if (loadError) return <Alert>Không thể tải chi tiết giao dịch. <Button variant="ghost" onClick={() => {
    setLoadError(false)
    setReload(value => value + 1)
  }}>Thử lại</Button></Alert>
  if (!detail) return <Skeleton lines={5} />

  const { transaction } = detail
  const { bookingCode, status } = transaction
  async function download() {
    setPdfBusy(true)
    setPdfError('')
    try { await bookingApi.downloadConfirmation(bookingCode, id) }
    catch (error) { setPdfError(errorMessage(error)) }
    finally { setPdfBusy(false) }
  }

  return <div className="transaction-detail">
    <div className="transaction-detail-heading">
      <div><p className="muted small">{transaction.roomTypeName}</p><h3>{bookingCode}</h3></div>
      <PaymentStatusBadge status={status} />
    </div>
    {status === 'PROCESSING' && <Alert tone="warning">Đang chờ xác minh thanh toán từ VNPay. Bạn có thể mở lại giao dịch để cập nhật.</Alert>}
    {status === 'REVIEW_REQUIRED' && <Alert tone="warning">Giao dịch đang được kiểm tra. Vui lòng liên hệ khách sạn nếu trạng thái không cập nhật.</Alert>}
    {status === 'EXPIRED' && transaction.provider === 'VNPAY' && !detail.gatewayVerifiedAt &&
      <Alert tone="warning">Thời gian thanh toán đã hết và giao dịch chưa được xác minh. Nếu đã thanh toán, vui lòng liên hệ khách sạn.</Alert>}
    <section className="transaction-breakdown">
      <h3>Chi tiết số tiền</h3>
      <dl>
        <div><dt>Giá phòng gốc</dt><dd>{formatVnd(detail.baseAmount)}</dd></div>
        <div><dt>Ưu đãi hạng thành viên</dt><dd>{discount(detail.membershipDiscount)}</dd></div>
        <div><dt>Giảm giá voucher</dt><dd>{discount(detail.voucherDiscount)}</dd></div>
        <div className="transaction-total"><dt>Tổng phải trả của lượt này</dt><dd>{formatVnd(detail.payableAmount)}</dd></div>
        <div><dt>Số tiền của giao dịch</dt><dd>{formatVnd(transaction.amount)} <small>{transaction.currency}</small></dd></div>
      </dl>
    </section>
    <section className="transaction-metadata">
      <h3>Thông tin thanh toán</h3>
      <dl>
        <div><dt>Mã tham chiếu</dt><dd>{transaction.reference}</dd></div>
        {detail.providerTransactionId && <div><dt>Mã giao dịch VNPay</dt><dd>{detail.providerTransactionId}</dd></div>}
        <div><dt>Phương thức</dt><dd>{paymentMethod(transaction)}</dd></div>
        <div><dt>Trạng thái đặt phòng</dt><dd><BookingStatusBadge status={transaction.bookingStatus} /></dd></div>
        <div><dt>Khởi tạo</dt><dd><time dateTime={transaction.initiatedAt}>{formatDateTime(transaction.initiatedAt)}</time></dd></div>
        {detail.gatewayVerifiedAt && <div><dt>Nhận kết quả đã xác minh</dt><dd><time dateTime={detail.gatewayVerifiedAt}>{formatDateTime(detail.gatewayVerifiedAt)}</time></dd></div>}
        {detail.settledAt && <div><dt>Đã tất toán</dt><dd><time dateTime={detail.settledAt}>{formatDateTime(detail.settledAt)}</time></dd></div>}
      </dl>
    </section>
    {pdfError && <Alert>{pdfError}</Alert>}
    <div className="dialog-actions">
      <LinkButton to={`/my-bookings/${encodeURIComponent(bookingCode)}`} variant="secondary">Xem chi tiết kỳ nghỉ</LinkButton>
      {transaction.receiptAvailable && <Button busy={pdfBusy} onClick={download}>Tải PDF xác nhận</Button>}
    </div>
  </div>
}

function discount(amount: number) {
  return amount > 0 ? `−${formatVnd(amount)}` : formatVnd(amount)
}
