import { bookingApi } from '../../booking/api'
import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError, errorMessage } from '../../../shared/api/client'
import { Alert, Button, Card, EmptyState, Field, LoadingState, PageHeader } from '../../../shared/ui'
import { FixtureNotice, USE_FIXTURES } from '../shared/fixtureMode'
import { statusLabels } from '../shared/bookingStatus'
import { formatHotelTime } from '../shared/staffTime'
import { searchBookings } from './bookingSearchApi'
import { bookingCalendarLink, bookingQuery, formatVnd } from './bookingSearchFormat'
import type { BookingSearchResult, SearchMode } from './bookingSearchTypes'
import './bookingSearch.css'

export function BookingSearchPage() {
  const navigate = useNavigate()
  const [mode, setMode] = useState<SearchMode>('bookingCode')
  const [input, setInput] = useState('')
  const [validation, setValidation] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<BookingSearchResult | null>(null)
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])

  function clear() {
    request.current?.abort(); request.current = null
    setBusy(false); setResult(null); setError(''); setValidation('')
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (request.current) return
    let query
    try { query = bookingQuery(mode, input) } catch (error) { setValidation(errorMessage(error)); return }
    const controller = new AbortController()
    request.current = controller
    setBusy(true); setResult(null); setError(''); setValidation('')
    try {
      const data = await searchBookings(query, controller.signal)
      if (!controller.signal.aborted) setResult(data)
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && error.status === 403) navigate('/403', { replace: true })
      else setError(errorMessage(error))
    } finally {
      if (request.current === controller) { request.current = null; setBusy(false) }
    }
  }

  return <div className="booking-search-page">
    <PageHeader eyebrow="KHÔNG GIAN NHÂN VIÊN" title="Tra cứu booking" description="Tìm đúng lượt đặt theo mã booking hoặc số điện thoại để hỗ trợ khách." />
    <FixtureNotice />
    {USE_FIXTURES && <p className="fixture-help">Dữ liệu mẫu: mã BK-MAU-001, BK-MAU-003 hoặc số điện thoại 0900000123.</p>}
    <Card><form className="booking-search-form" onSubmit={submit} noValidate autoComplete="off">
      <label className="staff-control">Tìm theo<select aria-label="Tìm theo" value={mode} onChange={event => { clear(); setMode(event.target.value as SearchMode); setInput('') }}>
        <option value="bookingCode">Mã booking</option><option value="phone">Số điện thoại</option>
      </select></label>
      <Field label={mode === 'bookingCode' ? 'Mã booking' : 'Số điện thoại'} type={mode === 'phone' ? 'tel' : 'text'}
        inputMode={mode === 'phone' ? 'tel' : 'text'} value={input} maxLength={mode === 'phone' ? 24 : 64} required
        autoComplete="off" autoCapitalize="none" spellCheck={false} error={validation}
        onChange={event => { clear(); setInput(event.target.value) }} hint="Nhập một giá trị cụ thể. Không liệt kê toàn bộ booking." />
      <Button type="submit" busy={busy}>Tra cứu</Button>
    </form></Card>
    <p className="booking-search-note">Thời gian theo giờ Việt Nam (UTC+07:00). Thông tin liên hệ chỉ hiển thị dạng đã che.</p>
    {busy && <LoadingState message="Đang tra cứu booking…" />}
    {error && <Alert>{error}</Alert>}
    {!busy && !error && !result && <p className="booking-search-note">{USE_FIXTURES ? 'Nhập mã booking hoặc số điện thoại để bắt đầu.' : 'Nhập mã booking hoặc số điện thoại để bắt đầu.'}</p>}
    {result && !result.items.length && <Card><EmptyState icon="calendar" title="Không tìm thấy booking" description="Kiểm tra lại mã booking hoặc số điện thoại và thử lại." /></Card>}
    {result && result.items.length > 0 && <>
      <p className="booking-result-count" role="status">Tìm thấy {result.items.length} booking.</p>
      <div className="booking-results">{result.items.map(item => <article className="card booking-result" key={item.bookingId} aria-label={`Booking ${item.bookingCode}`}>
        <div className="booking-result-heading"><h2><Link to={bookingCalendarLink(item)}>{item.bookingCode}</Link></h2>
          <span className={`booking-status booking-status--${item.status.toLowerCase()}`}>{statusLabels[item.status]}</span></div>
        {item.clientMaskedContact && <Alert tone="warning">Thông tin liên hệ đã được che tại giao diện. Cần Thanh Binh bổ sung che dữ liệu tại máy chủ.</Alert>}
        <dl><div><dt>Loại phòng</dt><dd>{item.roomTypeName ?? '—'}</dd></div>
          <div><dt>Số phòng</dt><dd>{item.roomNumber === undefined ? '—' : item.roomNumber ?? 'Chưa được gán phòng'}</dd></div>
          <div><dt>Nhận phòng</dt><dd>{formatHotelTime(item.startAt)}</dd></div>
          <div><dt>Trả phòng</dt><dd>{formatHotelTime(item.endAt)}</dd></div>
          <div><dt>Số khách</dt><dd>{item.guestCount}</dd></div>
          <div><dt>Số tiền phải trả</dt><dd className="booking-payable">{formatVnd(item.payableAmount)}</dd></div>
          {item.status === 'PENDING_PAYMENT' && <div><dt>Hạn giữ chỗ</dt><dd>{item.holdExpiresAt ? formatHotelTime(item.holdExpiresAt) : 'Máy chủ chưa cung cấp'}</dd></div>}
          {item.customer ? <><div><dt>Số điện thoại (đã che)</dt><dd>{item.customer.phoneMasked}</dd></div>
            <div><dt>Email (đã che)</dt><dd>{item.customer.emailMasked}</dd></div></> : <div><dt>Thông tin liên hệ</dt><dd>—</dd></div>}
        </dl>
        {['CONFIRMED', 'COMPLETED'].includes(item.status) &&
          <Button variant="secondary" onClick={() => bookingApi.downloadConfirmation(item.bookingCode).catch(error => setError(errorMessage(error)))}>Tải PDF xác nhận</Button>}
        <Link className="text-link" to={bookingCalendarLink(item)}>Xem tuần lịch của booking {item.bookingCode} →</Link>
      </article>)}</div>
    </>}
  </div>
}
