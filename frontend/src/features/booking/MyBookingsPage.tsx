import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { errorMessage } from '../../shared/api/client'
import { Alert, Badge, Button, Card, EmptyState, PageHeader } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { useAuth } from '../auth/AuthProvider'
import { addDays, hotelToday, roomBookingPath } from '../rooms/search'
import { bookingApi } from './api'
import { bookingStatusLabels, bookingStatusTones, formatDate, formatVnd } from './types'
import type { BookingResponse, RoomType } from './types'

export function MyBookingsPage() {
  const { user } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [myBookings, setMyBookings] = useState<BookingResponse[]>([])
  const [roomTypes, setRoomTypes] = useState<RoomType[]>([])
  const [loadingMyBookings, setLoadingMyBookings] = useState(true)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const fetchMyBookings = useCallback(async (signal?: AbortSignal) => {
    setLoadingMyBookings(true)
    setError('')
    try {
      const list = await bookingApi.getMyBookings(signal)
      if (!signal?.aborted) setMyBookings(list)
    } catch (error) {
      if (!signal?.aborted) setError(errorMessage(error))
    } finally {
      if (!signal?.aborted) setLoadingMyBookings(false)
    }
  }, [])

  useEffect(() => {
    if (!user) return
    const controller = new AbortController()
    void fetchMyBookings(controller.signal)
    bookingApi.getRoomTypes(controller.signal).then(rooms => {
      if (!controller.signal.aborted) setRoomTypes(rooms)
    }).catch(() => { /* Booking identifiers remain available if catalog metadata fails. */ })
    return () => controller.abort()
  }, [user, fetchMyBookings])

  async function handleCancelBooking(code: string) {
    setBusy(true)
    setError('')
    try {
      await bookingApi.cancel(code)
      await fetchMyBookings()
    } catch (error) { setError(errorMessage(error)) }
    finally { setBusy(false) }
  }

  function rebook(booking: BookingResponse) {
    const today = hotelToday()
    const previousArrival = hotelToday(new Date(booking.startAt))
    const past = previousArrival < today
    navigate(roomBookingPath(booking.roomTypeId, {
      arrival: past ? today : previousArrival,
      departure: past ? addDays(today, 1) : hotelToday(new Date(booking.endAt)),
      adults: Math.min(booking.guestCount, 10),
      children: Math.max(0, booking.guestCount - 10),
    }))
  }

  return <>
    <PageHeader title="Kỳ nghỉ của tôi" description="Xem đặt phòng và tiếp tục thanh toán đơn đang giữ." />
    {error && <Alert>{error}</Alert>}
    <Card>
      <div className="card-heading">
        <div>
          <h2>Đơn đặt phòng của tôi</h2>
        </div>
        <div className="button-row">
          {user?.roles.includes('CUSTOMER') && <Link to="/account/transactions" className="button button--secondary">Lịch sử giao dịch</Link>}
          <Button variant="ghost" busy={loadingMyBookings} onClick={() => void fetchMyBookings()}>
            <Icon name="refresh" size={16} /> Làm mới
          </Button>
        </div>
      </div>

      {!user ? (
        <EmptyState
          icon="user"
          title="Vui lòng đăng nhập"
          description="Đăng nhập tài khoản để xem toàn bộ lịch sử đơn đặt phòng và trạng thái thanh toán."
          action={
            <Link to="/login" state={{ from: location.pathname + location.search }} className="button button--primary">
              Đăng nhập tài khoản
            </Link>
          }
        />
      ) : loadingMyBookings ? (
        <div className="loading-state">
          <span className="spinner" />
          Đang tải danh sách đơn đặt phòng…
        </div>
      ) : myBookings.length === 0 ? (
        <EmptyState
          icon="calendar"
          title="Bạn chưa có đơn đặt phòng nào"
          description="Hãy bắt đầu chuyến đi của bạn bằng cách tìm kiếm loại phòng phù hợp."
          action={
            <Button variant="primary" onClick={() => navigate('/rooms')}>
              Tìm và đặt phòng ngay
            </Button>
          }
        />
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Mã đơn</th>
                <th>Loại phòng</th>
                <th>Thời gian lưu trú</th>
                <th>Số khách</th>
                <th>Tổng tiền</th>
                <th>Trạng thái</th>
                <th>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {myBookings.map((b) => {
                const room = roomTypes.find((r) => r.id === b.roomTypeId)
                const roomName = room ? room.name : `Loại phòng #${b.roomTypeId}`
                const isPending = b.status === 'PENDING_PAYMENT'

                return (
                  <tr key={b.id}>
                    <td>
                      <strong>{b.bookingCode}</strong>
                      <div className="small muted">{formatDate(b.createdAt)}</div>
                    </td>
                    <td>
                      <span>{roomName}</span>
                    </td>
                    <td>
                      <div>
                        {formatDate(b.startAt)} → {formatDate(b.endAt)}
                      </div>
                      <div className="small muted">Nhận 12:00 · Trả 10:00</div>
                    </td>
                    <td>{b.guestCount} khách</td>
                    <td>
                      <strong>{formatVnd(b.payableAmount)}</strong>
                      {b.discountAmount > 0 && (
                        <div className="small" style={{ color: '#2e7d32' }}>
                          Ưu đãi hạng: −{formatVnd(b.discountAmount)}
                        </div>
                      )}
                      {(b.voucherDiscountAmount ?? 0) > 0 && (
                        <div className="small" style={{ color: '#2e7d32' }}>
                          Mã {b.voucherCode}: −{formatVnd(b.voucherDiscountAmount ?? 0)}
                        </div>
                      )}
                    </td>
                    <td>
                      <Badge tone={bookingStatusTones[b.status]}>
                        {bookingStatusLabels[b.status]}
                      </Badge>
                    </td>
                    <td>
                      <div className="table-actions">
                        <Link className="button button--ghost button--small" to={`/my-bookings/${encodeURIComponent(b.bookingCode)}`}>Chi tiết kỳ nghỉ</Link>
                        {isPending && (
                          <>
                            <Button
                              variant="primary"
                              className="button--small"
                              onClick={() => navigate(`/booking?bookingCode=${encodeURIComponent(b.bookingCode)}`)}
                            >
                              Thanh toán
                            </Button>
                            <Button
                              variant="ghost"
                              className="button--small"
                              style={{ color: '#b25d53' }}
                              busy={busy}
                              onClick={() => handleCancelBooking(b.bookingCode)}
                            >
                              Hủy
                            </Button>
                          </>
                        )}

                        {b.status === 'CONFIRMED' && (
                          <Badge tone="success">
                            <Icon name="check" size={14} /> Đã xác nhận
                          </Badge>
                        )}

                        {b.status === 'COMPLETED' && (
                          <span className="small muted">Đã hoàn thành</span>
                        )}

                        {(b.status === 'CANCELLED' || b.status === 'EXPIRED') && (
                          <Button
                            variant="ghost"
                            className="button--small"
                            onClick={() => rebook(b)}
                          >
                            Đặt lại
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  </>
}
