import { useEffect, useRef } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { apiAssetUrl } from '../../shared/api/client'
import { Button, PriceDisplay } from '../../shared/ui'
import { bookingStatusLabels, formatDate, formatDateTime } from '../booking/types'
import type { BookingStatus } from '../booking/types'
import { RoomPhoto } from '../rooms/RoomCard'
import { roomBookingPath, stayParams } from '../rooms/search'
import type { ChatMessage, ConciergeAnswer, Conversation } from './api'

const sourceLabels = { LIVE_AI: 'Trợ lý AI', FAQ: 'Chính sách khách sạn', HOTEL_PMS: 'Hotel PMS', UNAVAILABLE: 'Trạng thái hỗ trợ' }
const paymentLabels: Record<string, string> = {
  SUCCEEDED: 'Đã thanh toán', PENDING: 'Chờ xác minh', FAILED: 'Chưa thành công',
  NOT_STARTED: 'Chưa khởi tạo', REVIEW_REQUIRED: 'Cần đối soát',
}
export const conversationLabels: Record<Conversation['status'], string> = {
  AI_ACTIVE: 'Trợ lý khách sạn', WAITING_STAFF: 'Đang chờ nhân viên', STAFF_ACTIVE: 'Nhân viên đang hỗ trợ', CLOSED: 'Đã kết thúc',
}

export function AnswerDetails({ answer, onNavigate, onLogin }: {
  answer: ConciergeAnswer; onNavigate?: () => void; onLogin?: () => void
}) {
  const location = useLocation()
  const stay = answer.arrival && answer.departure && answer.guests
    ? { arrival: answer.arrival, departure: answer.departure, adults: answer.guests, children: 0 } : undefined
  return <>
    {answer.loginRequired && <Link to="/login" onClick={onLogin ?? onNavigate}
      state={{ from: location.pathname + location.search + location.hash }}>Đăng nhập để xem booking</Link>}
    {answer.rooms.map(room => <article className="support-room" key={room.id}>
      <RoomPhoto src={room.coverImageUrl ? apiAssetUrl(room.coverImageUrl) : null} alt={`Ảnh bìa ${room.name}`} thumbnail />
      <strong>{room.name}</strong>
      {room.description && <p>{room.description}</p>}
      <p>Tối đa {room.capacity} khách{room.available != null && ` · Còn ${room.available} phòng`}</p>
      {room.amenities.length > 0 && <p>{room.amenities.join(' · ')}</p>}
      {stay && (room.payable != null ? <PriceDisplay amount={room.payable} suffix=" / kỳ nghỉ" /> : <p>Chưa lấy được báo giá.</p>)}
      <div className="support-actions">
        <Link to={`/rooms/${room.id}${stay ? `?${stayParams(stay)}` : ''}`} onClick={onNavigate}>Xem chi tiết</Link>
        {stay && room.available != null && room.available > 0 && room.payable != null &&
          <Link to={roomBookingPath(room.id, stay)} onClick={onNavigate}>Đặt ngay</Link>}
      </div>
    </article>)}
    {answer.bookings.map(booking => <article className="support-booking" key={booking.code}>
      <Link to={`/my-bookings/${encodeURIComponent(booking.code)}`} onClick={onNavigate}>{booking.code}</Link>
      <p>{booking.roomType} · {formatDate(booking.startAt)} – {formatDate(booking.endAt)}</p>
      <p>{bookingStatusLabels[booking.status as BookingStatus] ?? 'Đang kiểm tra'} · {paymentLabels[booking.paymentStatus] ?? 'Đang kiểm tra'}</p>
    </article>)}
  </>
}

export function SupportMessages({ messages, staff = false, older, onOlder, onNavigate, onLogin, pending, welcome }: {
  messages: ChatMessage[]; staff?: boolean; older?: boolean; onOlder?: () => void; onNavigate?: () => void
  onLogin?: () => void; pending?: boolean; welcome?: string
}) {
  const container = useRef<HTMLDivElement>(null)
  const previous = useRef<number | undefined>(undefined)
  const latest = messages.at(-1)?.id
  useEffect(() => {
    if (container.current && latest !== previous.current) container.current.scrollTop = container.current.scrollHeight
    previous.current = latest
  }, [latest])
  return <div ref={container} className="support-messages" role="log" aria-label="Tin nhắn hỗ trợ" aria-live="polite" tabIndex={0}>
    {older && <Button variant="ghost" onClick={onOlder}>Xem tin nhắn cũ hơn</Button>}
    {!messages.length && welcome && <p className="support-welcome">{welcome}</p>}
    {messages.map(message => <article key={message.id} className={`support-message${(staff ? message.sender === 'STAFF' : message.sender === 'CUSTOMER') ? ' support-message--own' : ''}`}>
      <header><strong>{message.sender === 'AI' ? sourceLabels[message.details?.source ?? 'HOTEL_PMS'] :
        message.sender === 'STAFF' ? 'Nhân viên' : staff ? 'Khách hàng' : 'Bạn'}</strong>
        <time dateTime={message.createdAt}>{formatDateTime(message.createdAt)}</time></header>
      <p>{message.content}</p>
      {message.details && <AnswerDetails answer={message.details} onNavigate={onNavigate} onLogin={onLogin} />}
    </article>)}
    {pending && <p role="status">Đang xử lý…</p>}
  </div>
}
