import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { errorMessage } from '../../shared/api/client'
import { Alert, Button, Card, Dialog, EmptyState, Field, PageHeader, Select, Skeleton, useToast } from '../../shared/ui'
import { BookingStatusBadge } from '../booking/BookingComponents'
import { formatDateTime } from '../booking/types'
import { checkInApi } from './checkInApi'
import type { Decision, QueueItem, RequestStatus } from './checkInApi'
import { CheckInStatus, requestLabels } from './CheckInStatus'
import './checkIn.css'

export function StaffCheckInPage() {
  const [status, setStatus] = useState<RequestStatus | ''>('PENDING')
  const [search, setSearch] = useState('')
  const [code, setCode] = useState('')
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<Awaited<ReturnType<typeof checkInApi.list>> | null>(null)
  const [error, setError] = useState('')
  const [reload, setReload] = useState(0)
  const [selected, setSelected] = useState<number | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    checkInApi.list(status, code, page, controller.signal).then(data => {
      if (!controller.signal.aborted) { setResult(data); setError('') }
    }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)) })
    return () => controller.abort()
  }, [status, code, page, reload])

  function refresh() {
    setResult(null)
    setError('')
    setReload(value => value + 1)
  }

  function searchBookings(event: FormEvent) {
    event.preventDefault()
    setCode(search.trim())
    setPage(0)
    refresh()
  }

  return <div className="checkin-workspace">
    <PageHeader eyebrow="ARRIVAL DESK" title="Yêu cầu nhận phòng trực tuyến"
      description="Xem giờ đến dự kiến, xử lý yêu cầu và tra lại lịch sử trước khi khách đến."
      action={<Button variant="secondary" onClick={refresh}>Tải lại danh sách</Button>} />
    <Card className="checkin-search"><form onSubmit={searchBookings}>
      <Field label="Tìm theo mã đặt phòng" value={search} maxLength={30} type="search" onChange={event => setSearch(event.target.value)} />
      <Select label="Trạng thái yêu cầu" value={status} onChange={event => {
        setStatus(event.target.value as RequestStatus | '')
        setPage(0)
        refresh()
      }}>
        <option value="">Tất cả trạng thái</option>
        {Object.entries(requestLabels).map(([value, label]) => <option value={value} key={value}>{label}</option>)}
      </Select>
      <Button type="submit">Tìm yêu cầu</Button>
    </form></Card>
    {error ? <Alert>{error} <Button variant="ghost" onClick={refresh}>Thử lại</Button></Alert> :
      !result ? <Card><Skeleton lines={5} /></Card> :
      result.items.length ? <ul className="checkin-queue" aria-label="Danh sách yêu cầu">
        {result.items.map(item => <li key={item.request.id}><Card className="checkin-queue-item">
          <div><p className="eyebrow">{item.bookingCode}</p><h2>{item.roomTypeName}</h2></div>
          <div><p className="small muted">Đến dự kiến</p><time dateTime={item.request.estimatedArrivalAt}>{formatDateTime(item.request.estimatedArrivalAt)}</time></div>
          <div><CheckInStatus status={item.request.status} /></div>
          <Button variant="secondary" onClick={() => setSelected(item.request.id)}>Xem yêu cầu</Button>
        </Card></li>)}
      </ul> : <Card><EmptyState icon="calendar" title="Không có yêu cầu phù hợp" description="Thử đổi trạng thái hoặc tìm theo mã đặt phòng khác." /></Card>}
    {result && (page > 0 || result.hasNext) && <nav className="pagination" aria-label="Phân trang yêu cầu nhận phòng">
      <Button variant="secondary" disabled={page === 0} onClick={() => { setPage(page - 1); refresh() }}>Trang trước</Button>
      <span aria-live="polite">Trang {page + 1}</span>
      <Button variant="secondary" disabled={!result.hasNext} onClick={() => { setPage(page + 1); refresh() }}>Trang sau</Button>
    </nav>}
    {selected !== null && <RequestDrawer key={selected} id={selected} close={() => setSelected(null)} onChange={refresh} />}
  </div>
}

function RequestDrawer({ id, close, onChange }: { id: number; close: () => void; onChange: () => void }) {
  const notify = useToast()
  const [item, setItem] = useState<QueueItem | null>(null)
  const [error, setError] = useState('')
  const [decision, setDecision] = useState<Decision | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    checkInApi.detail(id, controller.signal).then(data => { if (!controller.signal.aborted) setItem(data) })
      .catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)) })
    return () => controller.abort()
  }, [id, reload])

  async function review(event: FormEvent) {
    event.preventDefault()
    if (busy || !decision) return
    setError('')
    if (decision === 'REJECTED' && !reason.trim()) { setError('Nhập lý do từ chối.'); return }
    setBusy(true)
    try {
      const updated = await checkInApi.review(id, decision, decision === 'REJECTED' ? reason.trim() : undefined)
      setItem(updated)
      setDecision(null)
      notify(decision === 'APPROVED' ? 'Đã duyệt yêu cầu nhận phòng.' : 'Đã từ chối yêu cầu nhận phòng.')
      onChange()
    } catch (error) {
      const message = errorMessage(error)
      setError(message)
      notify(message)
      setReload(value => value + 1)
      onChange()
    } finally { setBusy(false) }
  }

  return <Dialog open title="Chi tiết yêu cầu nhận phòng" className="checkin-drawer" onClose={() => { if (!busy) close() }}>
    {error && <Alert>{error} {!item && <Button variant="ghost" onClick={() => { setError(''); setReload(value => value + 1) }}>Thử lại</Button>}</Alert>}
    {!item ? !error && <Skeleton /> : <>
      <div className="checkin-drawer-status"><CheckInStatus status={item.request.status} /><BookingStatusBadge status={item.bookingStatus} /></div>
      <dl className="details-list">
        <div><dt>Mã đặt phòng</dt><dd>{item.bookingCode}</dd></div>
        <div><dt>Hạng phòng</dt><dd>{item.roomTypeName} · {item.guestCount} khách</dd></div>
        <div><dt>Lịch lưu trú</dt><dd>{formatDateTime(item.startAt)} – {formatDateTime(item.endAt)}</dd></div>
        <div><dt>Giờ đến dự kiến</dt><dd>{formatDateTime(item.request.estimatedArrivalAt)}</dd></div>
        <div><dt>Đề nghị đến sớm</dt><dd>{item.request.earlyCheckInRequested ? 'Có' : 'Không'}</dd></div>
        <div><dt>Gửi lúc</dt><dd>{formatDateTime(item.request.createdAt)}</dd></div>
        {item.request.reviewedAt && <div><dt>Xử lý lúc</dt><dd>{formatDateTime(item.request.reviewedAt)} · Nhân viên #{item.request.reviewedBy}</dd></div>}
        {item.request.status === 'CANCELLED' && <div><dt>Rút yêu cầu lúc</dt><dd>{formatDateTime(item.request.updatedAt)}</dd></div>}
      </dl>
      {item.request.arrivalNote && <p className="journey-note">{item.request.arrivalNote}</p>}
      {item.request.rejectionReason && <p className="journey-note">Lý do từ chối: {item.request.rejectionReason}</p>}
      <p className="small muted">{item.request.status === 'INVALIDATED' ?
        'Đặt phòng đã kết thúc hoặc không còn đủ điều kiện xử lý.' : 'Duyệt yêu cầu để chuẩn bị đón khách. Thủ tục nhận phòng tại khách sạn được thực hiện riêng.'}</p>
      {item.request.status === 'PENDING' && (decision ? <form className="checkin-form" onSubmit={review}>
        <h3>{decision === 'APPROVED' ? 'Xác nhận duyệt yêu cầu?' : 'Từ chối yêu cầu'}</h3>
        {decision === 'REJECTED' && <label className="field">Lý do từ chối<textarea value={reason} onChange={event => setReason(event.target.value)}
          required maxLength={300} rows={3} disabled={busy} /></label>}
        <div className="dialog-actions"><Button variant="secondary" onClick={() => { setDecision(null); setError('') }} disabled={busy}>Quay lại</Button>
          <Button type="submit" busy={busy}>{decision === 'APPROVED' ? 'Xác nhận duyệt' : 'Xác nhận từ chối'}</Button></div>
      </form> : <div className="dialog-actions">
        <Button variant="secondary" onClick={() => setDecision('REJECTED')}>Từ chối</Button>
        <Button onClick={() => setDecision('APPROVED')}>Duyệt yêu cầu</Button>
      </div>)}
    </>}
  </Dialog>
}
