import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useParams } from 'react-router-dom'
import { apiAssetUrl, errorMessage } from '../../shared/api/client'
import { Alert, Button, Card, ConfirmDialog, Field, LinkButton, PageHeader, Skeleton, useToast } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { bookingApi } from '../booking/api'
import { BookingStatusBadge } from '../booking/BookingComponents'
import { formatDateTime, formatVnd } from '../booking/types'
import { checkInApi } from './checkInApi'
import type { BookingTimeline, Stage } from './checkInApi'
import { CheckInStatus } from './CheckInStatus'
import './checkIn.css'

const stages: Record<Stage, string> = {
  CREATED: 'Đã tạo đặt phòng', PAYMENT_PENDING: 'Chờ thanh toán', PAYMENT_SUCCEEDED: 'Thanh toán thành công',
  CONFIRMED: 'Đã xác nhận đặt phòng', CHECK_IN_REQUESTED: 'Đã gửi yêu cầu nhận phòng',
  CHECK_IN_APPROVED: 'Nhân viên đã duyệt yêu cầu', CHECK_IN_REJECTED: 'Nhân viên đã từ chối yêu cầu',
  CHECK_IN_CANCELLED: 'Đã rút yêu cầu', COMPLETED: 'Đặt phòng đã hoàn tất', CANCELLED: 'Đặt phòng đã hủy', EXPIRED: 'Đã hết hạn giữ phòng',
}

export function BookingTimelinePage() {
  const { bookingCode = '' } = useParams()
  return <BookingTimelineContent key={bookingCode} code={bookingCode} />
}

function BookingTimelineContent({ code }: { code: string }) {
  const notify = useToast()
  const [data, setData] = useState<BookingTimeline | null>(null)
  const [loadError, setLoadError] = useState('')
  const [actionError, setActionError] = useState('')
  const [reload, setReload] = useState(0)
  const [busy, setBusy] = useState(false)
  const [pdfBusy, setPdfBusy] = useState(false)
  const [cancelId, setCancelId] = useState<number | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    checkInApi.timeline(code, controller.signal).then(result => {
      if (!controller.signal.aborted) { setData(result); setLoadError('') }
    }).catch(error => { if (!controller.signal.aborted) setLoadError(errorMessage(error)) })
    return () => controller.abort()
  }, [code, reload])

  function refresh() {
    setData(null)
    setLoadError('')
    setReload(value => value + 1)
  }

  async function changeRequest(action: () => Promise<unknown>, success: string) {
    setBusy(true)
    setActionError('')
    try {
      await action()
      notify(success)
      refresh()
    } catch (error) {
      const message = errorMessage(error)
      setActionError(message)
      notify(message)
    } finally { setBusy(false) }
  }

  async function download() {
    setPdfBusy(true)
    setActionError('')
    try { await bookingApi.downloadConfirmation(code) }
    catch (error) { setActionError(errorMessage(error)) }
    finally { setPdfBusy(false) }
  }

  return <div className="journey-page">
    <PageHeader eyebrow="YOUR STAY" title="Chi tiết kỳ nghỉ" description={`Mã đặt phòng ${code}`}
      action={<LinkButton to="/my-bookings" variant="secondary">Kỳ nghỉ của tôi</LinkButton>} />
    {actionError && <Alert>{actionError} <Button variant="ghost" onClick={refresh}>Tải lại</Button></Alert>}
    {loadError ? <Alert>{loadError} <Button variant="ghost" onClick={refresh}>Thử lại</Button></Alert> :
      !data ? <Card><Skeleton lines={5} /></Card> : <>
        <StaySummary data={data} download={download} pdfBusy={pdfBusy} />
        <div className="journey-grid">
          <Card>
            <h2>Tiến trình đặt phòng</h2>
            <ol className="journey-timeline">{data.milestones.map(({ stage, at }, index) => <li key={`${stage}-${at}-${index}`}>
              <span className="journey-marker" aria-hidden="true"><Icon name={stage === 'CANCELLED' || stage === 'EXPIRED' ? 'close' : stage === 'PAYMENT_PENDING' ? 'clock' : 'check'} size={15} /></span>
              <div><strong>{stages[stage]}</strong><time dateTime={at}>{formatDateTime(at)}</time></div>
            </li>)}</ol>
            {data.eligibility.allowed && <p className="small">Bạn có thể gửi yêu cầu nhận phòng trực tuyến.</p>}
            <p className="small muted">Các mốc dựa trên dữ liệu đặt phòng. Việc nhận phòng tại khách sạn được thực hiện riêng.</p>
            <Button variant="ghost" onClick={refresh} disabled={busy}><Icon name="refresh" size={17} />Cập nhật tiến trình</Button>
          </Card>
          <Card>
            <h2>Yêu cầu nhận phòng trực tuyến</h2>
            <p className="muted small">Báo trước giờ đến để nhân viên chuẩn bị. Sau khi được duyệt, bạn vẫn cần làm thủ tục tại khách sạn.</p>
            {data.requests.map(item => <div className="journey-request" key={item.id}>
              <div><CheckInStatus status={item.status} /><span className="small muted">Gửi {formatDateTime(item.createdAt)}</span></div>
              <p>Đến dự kiến <strong>{formatDateTime(item.estimatedArrivalAt)}</strong>{item.earlyCheckInRequested ? ' · Có đề nghị đến sớm' : ''}</p>
              {item.reviewedAt && <p className="small muted">Xử lý {formatDateTime(item.reviewedAt)}</p>}
              {item.rejectionReason && <p>Lý do từ chối: {item.rejectionReason}</p>}
              {item.arrivalNote && <p className="journey-note">{item.arrivalNote}</p>}
              {item.status === 'PENDING' && <Button variant="secondary" disabled={busy} onClick={() => setCancelId(item.id)}>Rút yêu cầu</Button>}
            </div>)}
            {data.eligibility.allowed ? <ArrivalForm busy={busy} closesAt={data.eligibility.closesAt} submit={(estimatedArrival, earlyCheckInRequested, arrivalNote) =>
              changeRequest(() => checkInApi.submit(code, { estimatedArrival, earlyCheckInRequested, arrivalNote }), 'Đã gửi yêu cầu nhận phòng.')} /> :
              <Alert tone="neutral">{data.eligibility.reason} {data.booking.status === 'CONFIRMED' && !data.requests.some(item => item.status === 'APPROVED' || item.status === 'PENDING') &&
                <p className="small">Thời gian tiếp nhận {formatDateTime(data.eligibility.opensAt)} – {formatDateTime(data.eligibility.closesAt)}.</p>}</Alert>}
          </Card>
        </div>
      </>}
    <ConfirmDialog open={cancelId !== null} title="Rút yêu cầu nhận phòng" description="Bạn có thể gửi lại khi đặt phòng còn đủ điều kiện."
      confirmLabel="Rút yêu cầu" onClose={() => setCancelId(null)} onConfirm={() => {
        if (cancelId !== null) void changeRequest(() => checkInApi.cancel(code, cancelId), 'Đã rút yêu cầu nhận phòng.')
      }} />
  </div>
}

function StaySummary({ data, download, pdfBusy }: { data: BookingTimeline; download: () => void; pdfBusy: boolean }) {
  const { booking, room, payment } = data
  const paymentLabel = payment?.status === 'SUCCEEDED' ? (payment.provider === 'DEMO' ? 'Đã thanh toán demo' : 'Đã thanh toán') :
    payment?.status === 'FAILED' ? 'Lần thanh toán gần nhất thất bại' : payment?.status === 'PENDING' || booking.status === 'PENDING_PAYMENT' ?
      'Chờ thanh toán' : 'Chưa có thanh toán thành công'
  return <Card className="journey-summary">
    {room.coverImageUrl ? <img className="journey-cover" src={apiAssetUrl(room.coverImageUrl)} alt={`Ảnh bìa ${room.name}`} /> :
      <div className="journey-cover journey-cover--empty" aria-label="Chưa có ảnh phòng"><Icon name="bed" size={42} /></div>}
    <div className="journey-stay"><div className="card-heading"><h2>{room.name}</h2><BookingStatusBadge status={booking.status} /></div>
      <dl className="details-list">
        <div><dt>Ngày đến</dt><dd>{formatDateTime(booking.startAt)}</dd></div>
        <div><dt>Ngày đi</dt><dd>{formatDateTime(booking.endAt)}</dd></div>
        <div><dt>Thời gian lưu trú</dt><dd>{data.nights} đêm · {booking.guestCount} khách</dd></div>
        <div><dt>Thanh toán</dt><dd>{paymentLabel}</dd></div>
      </dl>
      {data.confirmationPdfAvailable && <Button variant="secondary" busy={pdfBusy} onClick={download}>Tải PDF xác nhận</Button>}
    </div>
    <dl className="details-list journey-prices">
      <div><dt>Giá phòng gốc</dt><dd>{formatVnd(booking.baseAmount)}</dd></div>
      <div><dt>Ưu đãi thành viên</dt><dd>{formatVnd(booking.discountAmount)}</dd></div>
      <div><dt>Ưu đãi voucher{booking.voucherCode ? ` (${booking.voucherCode})` : ''}</dt><dd>{formatVnd(booking.voucherDiscountAmount ?? 0)}</dd></div>
      <div className="summary-total"><dt>Tổng sau ưu đãi</dt><dd><strong>{formatVnd(booking.payableAmount)}</strong></dd></div>
    </dl>
  </Card>
}

function ArrivalForm({ busy, closesAt, submit }: {
  busy: boolean; closesAt: string; submit: (arrival: string, early: boolean, note: string) => void
}) {
  const [arrival, setArrival] = useState('')
  const [early, setEarly] = useState(false)
  const [note, setNote] = useState('')
  function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (!busy) submit(arrival, early, note.trim())
  }
  return <form className="checkin-form" onSubmit={onSubmit}>
    <Field label="Giờ đến dự kiến" type="datetime-local" required value={arrival} disabled={busy}
      hint={`Theo giờ Việt Nam (UTC+7). Hạn gửi ${formatDateTime(closesAt)}.`} onChange={event => setArrival(event.target.value)} />
    <label className="checkin-checkbox"><input type="checkbox" checked={early} disabled={busy} onChange={event => setEarly(event.target.checked)} /> Đề nghị nhận phòng sớm</label>
    <label className="field">Ghi chú giờ đến (tùy chọn)<textarea value={note} maxLength={500} rows={3} disabled={busy} onChange={event => setNote(event.target.value)} /></label>
    <Button type="submit" busy={busy}>Gửi yêu cầu</Button>
  </form>
}
