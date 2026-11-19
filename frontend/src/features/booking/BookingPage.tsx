import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { apiAssetUrl, errorMessage } from '../../shared/api/client'
import { Alert, Button, Card, LinkButton, LoadingState, PageHeader, PriceDisplay } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { useAuth } from '../auth/AuthProvider'
import { RoomPhoto } from '../rooms/RoomCard'
import { StaySearchForm } from '../rooms/StaySearchForm'
import { displayDate, hasStayDates, hotelToday, nightsBetween, parseRoomTypeId, readRequestedStay, stayParams } from '../rooms/search'
import type { StaySearch } from '../rooms/search'
import { useSelectedRoom } from '../rooms/useSelectedRoom'
import { roomApi } from '../rooms/api'
import type { RoomTypeResponse } from '../rooms/api'
import { bookingApi } from './api'
import { BookingStatusBadge } from './BookingComponents'
import { formatHoldTime, holdSecondsRemaining } from './hold'
import { toCheckInIso, toCheckOutIso } from './types'
import type { BookingResponse } from './types'
import { VnpayPayment } from './VnpayPayment'
import { VoucherInput } from './VoucherInput'
import './bookingReview.css'

export function BookingPage() {
  const [params] = useSearchParams()
  const code = params.get('bookingCode')
  const id = parseRoomTypeId(params.get('roomTypeId'))
  const { user, status, retry } = useAuth()
  const location = useLocation()
  const from = location.pathname + location.search
  const catalog = hasStayDates(params) ? `/rooms?${stayParams(readRequestedStay(params))}` : '/rooms'

  if (code !== null) {
    if (!/^BK[0-9A-Z-]{1,28}$/.test(code)) return <BookingProblem title="Mã đặt phòng không hợp lệ." to="/my-bookings" label="Kỳ nghỉ của tôi" />
    if (status === 'loading') return <LoadingState message="Đang xác thực phiên đăng nhập…" />
    if (status === 'unavailable') return <Card><Alert>Chưa thể xác thực tài khoản.</Alert><Button onClick={retry}>Thử lại</Button></Card>
    if (!user) return <Card><h1>Đăng nhập để tiếp tục thanh toán</h1><Link className="button button--primary" to="/login" state={{ from }}>Đăng nhập</Link></Card>
    if (!user.roles.includes('CUSTOMER')) return <BookingProblem title="Trang thanh toán dành cho khách hàng." to="/rooms" label="Xem danh mục phòng" />
    return <HeldBooking key={`${user.id}:${code}`} code={code} customerId={user.id} />
  }

  if (id === null) return <BookingProblem title={params.has('roomTypeId') ? 'Hạng phòng không hợp lệ.' : 'Bạn chưa chọn hạng phòng.'}
    to={catalog} label="Chọn phòng" />
  return <BookingReview key={`${user?.id ?? 'guest'}:${params}`} id={id}
    stay={hasStayDates(params) ? readRequestedStay(params) : null} catalog={catalog} />
}

function BookingProblem({ title, to, label }: { title: string; to: string; label: string }) {
  return <Card><h1>{title}</h1><LinkButton to={to}>{label}</LinkButton></Card>
}

function BookingReview({ id, stay, catalog }: { id: number; stay: StaySearch | null; catalog: string }) {
  const { user, status, retry: retryAuth } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const { room, roomError, current, validation, retry } = useSelectedRoom(id, stay)
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState('')
  const inFlight = useRef(false)
  const active = useRef(true)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const quote = current?.quote

  async function reserve() {
    if (!stay || !quote || !current?.available || !user?.roles.includes('CUSTOMER') || inFlight.current) return
    inFlight.current = true
    setBusy(true)
    setFailure('')
    const query = new URLSearchParams(stayParams(stay))
    query.set('roomTypeId', String(id))
    try {
      const booking = await bookingApi.create({ roomTypeId: id, guestCount: stay.adults + stay.children,
        startAt: toCheckInIso(stay.arrival), endAt: toCheckOutIso(stay.departure) })
      if (active.current) {
        query.set('bookingCode', booking.bookingCode)
        navigate(`/booking?${query}`, { replace: true })
      }
    } catch (error) {
      if (active.current) { setFailure(errorMessage(error)); retry() }
    } finally {
      inFlight.current = false
      if (active.current) setBusy(false)
    }
  }

  return <div className="booking-review">
    <PageHeader title="Xem lại đặt phòng" description="Kiểm tra hạng phòng, lịch nghỉ và tổng tiền trước khi giữ chỗ."
      action={<Link to={catalog} className="text-link">Đổi hạng phòng <Icon name="arrow" size={16} /></Link>} />
    <BookingProgress step={1} />
    {roomError ? <Card><Alert>{roomError}</Alert><Button variant="secondary" onClick={retry}>Thử lại</Button></Card>
      : !room ? <LoadingState message="Đang tải hạng phòng đã chọn…" /> : <div className="booking-review-grid">
        <div className="booking-review-main">
          <SelectedRoom room={room} />
          <Card><div className="booking-panel-heading"><h2>Lịch nghỉ</h2>
            {stay && <Button variant="ghost" disabled={busy} onClick={() => setEditing(value => !value)}>{editing ? 'Đóng chỉnh sửa' : 'Chỉnh sửa'}</Button>}
          </div>
            {stay && <StayDetails arrival={stay.arrival} departure={stay.departure} guests={stay.adults + stay.children} />}
            {(!stay || editing || validation) && <StaySearchForm compact destination="/booking" roomTypeId={id} onApplied={() => setEditing(false)} />}
            {validation && <Alert tone="warning">{validation}</Alert>}
            {stay && !validation && (!current ? <LoadingState message="Đang kiểm tra phòng trống và báo giá…" />
              : current.error ? <><Alert>{current.error}</Alert><Button variant="secondary" onClick={retry}>Thử lại báo giá</Button></>
              : !current.available ? <Alert tone="warning">{stay.adults + stay.children > room.maxOccupancy
                ? 'Số khách vượt sức chứa của hạng phòng này.' : 'Hạng phòng này đã hết phòng trong khoảng ngày bạn chọn.'} <Link to={catalog}>Xem hạng phòng khác</Link></Alert>
              : <p className="booking-available"><Icon name="check" size={17} /> Còn phòng theo lịch đã chọn</p>)}
          </Card>
          {failure && <Alert>{failure}</Alert>}
        </div>
        <Card className="booking-price-panel"><h2>Tổng tiền kỳ nghỉ</h2>
          {quote ? <><PriceBreakdown price={quote} /><p className="small muted">Giá được chốt khi giữ phòng. Bạn có thể áp dụng voucher sau bước này.</p></>
            : <p className="muted">Báo giá sẽ hiển thị khi xác nhận còn phòng theo lịch đã chọn.</p>}
          {status === 'unavailable' ? <Button variant="secondary" onClick={retryAuth}>Kiểm tra lại phiên đăng nhập</Button>
            : user?.roles.includes('CUSTOMER')
            ? <Button busy={busy} disabled={!quote || editing} onClick={reserve}>Tiếp tục giữ phòng <Icon name="arrow" size={17} /></Button>
            : !user && quote && !editing ? <Link className="button button--primary" to="/login" state={{ from: location.pathname + location.search }}>Đăng nhập để giữ phòng</Link>
            : user && <p className="small muted">Dùng tài khoản khách hàng để giữ phòng.</p>}
        </Card>
      </div>}
  </div>
}

function HeldBooking({ code, customerId }: { code: string; customerId: number }) {
  const [params] = useSearchParams()
  const [booking, setBooking] = useState<BookingResponse>()
  const [room, setRoom] = useState<RoomTypeResponse>()
  const [failure, setFailure] = useState('')
  const [actionError, setActionError] = useState('')
  const [busy, setBusy] = useState(false)
  const [syncing, setSyncing] = useState(true)
  const [revision, setRevision] = useState(0)
  const [now, setNow] = useState(Date.now)
  const mutation = useRef({ busy: false, version: 0 })
  const pending = booking?.status === 'PENDING_PAYMENT'
  const terminal = booking !== undefined && !pending
  const remaining = booking ? holdSecondsRemaining(booking.holdExpiresAt, now) : null
  const expired = remaining === 0
  const finished = booking?.status === 'CONFIRMED' || booking?.status === 'COMPLETED'
  const roomId = booking?.roomTypeId

  useEffect(() => {
    if (terminal) return
    const controller = new AbortController()
    let inFlight = false
    async function refresh() {
      if (inFlight || mutation.current.busy || controller.signal.aborted) return
      const { version } = mutation.current
      inFlight = true
      setSyncing(true)
      try {
        const latest = await bookingApi.getByCode(code, controller.signal)
        if (controller.signal.aborted || mutation.current.version !== version) return
        if (latest.customerId !== customerId) throw new Error('Không tìm thấy đơn đặt phòng của bạn.')
        setBooking(latest)
        setFailure('')
      } catch (error) {
        if (!controller.signal.aborted && mutation.current.version === version) setFailure(errorMessage(error))
      } finally {
        inFlight = false
        if (!controller.signal.aborted) setSyncing(false)
      }
    }
    const visible = () => { if (document.visibilityState === 'visible') { setNow(Date.now()); void refresh() } }
    void refresh()
    const poll = setInterval(() => void refresh(), 5000)
    document.addEventListener('visibilitychange', visible)
    window.addEventListener('focus', visible)
    return () => {
      controller.abort()
      clearInterval(poll)
      document.removeEventListener('visibilitychange', visible)
      window.removeEventListener('focus', visible)
    }
  }, [code, customerId, terminal, revision, expired])

  useEffect(() => {
    if (!pending) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [pending])

  useEffect(() => {
    if (!roomId) return
    const controller = new AbortController()
    roomApi.get(roomId, controller.signal).then(value => {
      if (!controller.signal.aborted && value.id === roomId) setRoom(value)
    }).catch(() => { if (!controller.signal.aborted) setRoom(undefined) })
    return () => controller.abort()
  }, [roomId])

  function markBusy(value: boolean) {
    mutation.current = { busy: value, version: mutation.current.version + 1 }
    setBusy(value)
    if (!value) setRevision(current => current + 1)
  }

  async function changeBooking(action: (code: string) => Promise<BookingResponse>) {
    if (!booking || mutation.current.busy) return
    markBusy(true)
    setActionError('')
    try { setBooking(await action(code)) }
    catch (error) { setActionError(errorMessage(error)) }
    finally { markBusy(false) }
  }

  if (!booking) return failure ? <Card><Alert>{failure}</Alert><div className="button-row">
    <Button onClick={() => { setFailure(''); setRevision(value => value + 1) }}>Thử lại</Button>
    <LinkButton to="/my-bookings" variant="secondary">Kỳ nghỉ của tôi</LinkButton>
  </div></Card> : <LoadingState message="Đang tải đơn đặt phòng…" />

  const arrival = hotelToday(new Date(booking.startAt))
  const departure = hotelToday(new Date(booking.endAt))
  const source = readRequestedStay(params)
  const splitMatches = Number.isInteger(source.adults) && Number.isInteger(source.children)
    && source.adults >= 1 && source.adults <= 10 && source.children >= 0 && source.children <= 10
    && source.adults + source.children === booking.guestCount
  const catalog = `/rooms?${stayParams({ arrival, departure, adults: splitMatches ? source.adults : Math.min(10, booking.guestCount),
    children: splitMatches ? source.children : Math.max(0, booking.guestCount - 10) })}`
  const disabled = busy || syncing || Boolean(failure) || remaining === null || expired
  const zero = booking.payableAmount === 0
  const holdEnded = booking.status === 'EXPIRED' || expired

  return <div className="booking-review">
    <PageHeader title={finished ? 'Đặt phòng đã được xác nhận' : pending && !expired ? 'Giữ phòng & thanh toán' : 'Trạng thái đặt phòng'}
      description={`Mã đặt phòng ${booking.bookingCode}`} action={<LinkButton to="/my-bookings" variant="secondary">Kỳ nghỉ của tôi</LinkButton>} />
    <BookingProgress step={finished ? 4 : pending && !holdEnded && !failure ? 3 : 2} />
    <div className="booking-review-grid">
      <div className="booking-review-main">
        {room && <SelectedRoom room={room} />}
        <Card><div className="booking-panel-heading"><h2>Lịch nghỉ</h2><BookingStatusBadge status={booking.status} /></div>
          <StayDetails arrival={arrival} departure={departure} guests={booking.guestCount} />
        </Card>
        {holdEnded && !finished && booking.status !== 'CANCELLED' && <Alert tone="warning">
          {booking.status === 'EXPIRED' ? 'Máy chủ xác nhận đơn đặt phòng đã hết hạn.' : 'Thời gian giữ phòng đã kết thúc.'} Hãy kiểm tra lại phòng trống để tiếp tục.
        </Alert>}
        {failure && <Alert>{failure} <Button variant="ghost" onClick={() => setRevision(value => value + 1)}>Kiểm tra lại trạng thái</Button></Alert>}
        {actionError && <Alert>{actionError}</Alert>}
        {pending && !holdEnded && <Card><div className="booking-panel-heading"><h2>Giữ phòng trong 15 phút</h2>
          {remaining !== null && <span className="booking-clock" role="timer" aria-label={`Còn ${Math.floor(remaining / 60)} phút ${remaining % 60} giây`}>{formatHoldTime(remaining)}</span>}
        </div>
          {remaining === null ? <Alert tone="warning">Chưa xác định được thời hạn giữ phòng.</Alert>
            : <p className="muted">Hoàn tất thanh toán trước khi hết thời gian giữ phòng.</p>}
          <VoucherInput key={code} booking={booking} disabled={disabled} onChange={setBooking} onBusyChange={markBusy} />
          <Button variant="ghost" disabled={busy} onClick={() => void changeBooking(bookingApi.cancel)}>Hủy giữ phòng</Button>
        </Card>}
      </div>
      <Card className="booking-price-panel"><h2>Tổng tiền kỳ nghỉ</h2><PriceBreakdown price={booking} />
        {finished ? <Button onClick={() => bookingApi.downloadConfirmation(code).catch(error => setActionError(errorMessage(error)))}>Tải PDF xác nhận</Button>
          : holdEnded || booking.status === 'CANCELLED' ? <LinkButton to={catalog}>Kiểm tra phòng trống</LinkButton>
            : zero ? <Button disabled={disabled} busy={busy} onClick={() => void changeBooking(bookingApi.settleZero)}>Xác nhận đặt phòng 0₫</Button>
              : <VnpayPayment key={code} bookingCode={code} disabled={disabled} onBusyChange={markBusy} />}
      </Card>
    </div>
  </div>
}

function BookingProgress({ step }: { step: number }) {
  return <ol className="booking-review-progress" aria-label="Tiến trình đặt phòng">
    {['Xem lại đặt phòng', 'Giữ phòng', 'Thanh toán'].map((label, index) => {
      const number = index + 1
      return <li key={label} aria-current={step === number ? 'step' : undefined} data-complete={step > number}>
        <span className="booking-step-number" aria-hidden="true">{step > number ? <Icon name="check" size={16} /> : number}</span>
        <span>{label}{step > number && <span className="sr-only"> · Hoàn tất</span>}</span>
      </li>
    })}
  </ol>
}

function SelectedRoom({ room }: { room: RoomTypeResponse }) {
  return <Card className="booking-selected-room">
    <div className="booking-selected-photo"><RoomPhoto key={room.coverImageUrl} src={room.coverImageUrl ? apiAssetUrl(room.coverImageUrl) : null}
      alt={`Ảnh bìa ${room.name}`} thumbnail eager /></div>
    <div><h2>{room.name}</h2>{room.description && <p>{room.description}</p>}<p className="muted">Tối đa {room.maxOccupancy} khách</p>
      {room.amenities?.length ? <ul className="amenity-list">{room.amenities.map(item => <li key={item}><Icon name="check" size={14} />{item}</li>)}</ul> : null}
    </div>
  </Card>
}

function StayDetails({ arrival, departure, guests }: { arrival: string; departure: string; guests: number }) {
  const nights = nightsBetween(arrival, departure)
  return <dl className="booking-stay-details">
    <div><dt>Nhận phòng · 12:00</dt><dd>{displayDate(arrival)}</dd></div>
    <div><dt>Trả phòng · 10:00</dt><dd>{displayDate(departure)}</dd></div>
    <div><dt>Thời gian lưu trú</dt><dd>{nights === null ? '—' : `${nights} đêm`}</dd></div>
    <div><dt>Số khách</dt><dd>{Number.isFinite(guests) ? `${guests} khách` : '—'}</dd></div>
  </dl>
}

function PriceBreakdown({ price }: { price: { baseAmount: number; discountAmount: number; payableAmount: number; voucherDiscountAmount?: number } }) {
  return <dl className="booking-price-lines">
    <div><dt>Giá phòng gốc</dt><dd><PriceDisplay amount={price.baseAmount} /></dd></div>
    <div><dt>Ưu đãi thành viên</dt><dd>{price.discountAmount > 0 ? '−' : ''}<PriceDisplay amount={price.discountAmount} /></dd></div>
    {Boolean(price.voucherDiscountAmount) && <div><dt>Giảm giá voucher</dt><dd>−<PriceDisplay amount={price.voucherDiscountAmount} /></dd></div>}
    <div className="booking-total"><dt>Tổng tiền cần thanh toán</dt><dd><PriceDisplay amount={price.payableAmount} /></dd></div>
  </dl>
}
