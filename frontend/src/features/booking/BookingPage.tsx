import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import { bookingApi, calculateQuote } from './api'
import {
  DEFAULT_ROOM_TYPES,
  formatDate,
  formatVnd,
  getTodayDateString,
  getTomorrowDateString,
} from './types'
import type { RoomType } from './types'
import { Alert, Badge, Button, Card, Field, PageHeader } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'

export function BookingPage() {
  const { user } = useAuth()

  // State: Dates & Guests
  const [checkInDate, setCheckInDate] = useState(() => getTodayDateString())
  const [checkOutDate, setCheckOutDate] = useState(() => getTomorrowDateString(getTodayDateString()))
  const [guestCount, setGuestCount] = useState<number>(2)

  // State: Room Types & Selection
  const [roomTypes, setRoomTypes] = useState<RoomType[]>(DEFAULT_ROOM_TYPES)
  const [selectedRoomId, setSelectedRoomId] = useState<number>(DEFAULT_ROOM_TYPES[0].id)
  const [loadingRooms, setLoadingRooms] = useState(false)

  // State: Member Tier Preview (default to STANDARD, or VIP/DIAMOND)
  const [selectedTier, setSelectedTier] = useState<'STANDARD' | 'VIP' | 'DIAMOND'>('STANDARD')

  // Load Room Types from API (with safe fallback)
  useEffect(() => {
    const controller = new AbortController()
    setLoadingRooms(true)
    bookingApi
      .getRoomTypes(controller.signal)
      .then((data) => {
        if (!controller.signal.aborted && data.length > 0) {
          setRoomTypes(data)
          if (!data.some((r) => r.id === selectedRoomId)) {
            setSelectedRoomId(data[0].id)
          }
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoadingRooms(false)
      })
    return () => controller.abort()
  }, [])

  // Selected Room
  const selectedRoom = useMemo(() => {
    return roomTypes.find((r) => r.id === selectedRoomId) ?? roomTypes[0]
  }, [roomTypes, selectedRoomId])

  // Calculated Real-time Quote
  const quote = useMemo(() => {
    return calculateQuote(selectedRoom, checkInDate, checkOutDate, guestCount, selectedTier)
  }, [selectedRoom, checkInDate, checkOutDate, guestCount, selectedTier])

  // Handle Check-in date change
  function handleCheckInChange(newCheckIn: string) {
    setCheckInDate(newCheckIn)
    if (newCheckIn >= checkOutDate) {
      setCheckOutDate(getTomorrowDateString(newCheckIn))
    }
  }

  // Handle Check-out date change
  function handleCheckOutChange(newCheckOut: string) {
    if (newCheckOut > checkInDate) {
      setCheckOutDate(newCheckOut)
    }
  }

  // Validation
  const exceedsOccupancy = guestCount > selectedRoom.maxOccupancy
  const isInvalidDates = checkOutDate <= checkInDate

  return (
    <div className="booking-container">
      <PageHeader
        eyebrow="KỲ NGHỈ CỦA BẠN"
        title="Tìm và đặt phòng"
        description="Lựa chọn thời gian nghỉ và nhận báo giá phòng theo tiêu chuẩn thời gian thực."
      />

      {/* Stepper */}
      <div className="booking-steps" aria-label="Các bước đặt phòng">
        <span className="current">
          <b>1</b>Chọn phòng & Báo giá
        </span>
        <i />
        <span>
          <b>2</b>Xác nhận giữ chỗ (15p)
        </span>
        <i />
        <span>
          <b>3</b>Thanh toán Demo
        </span>
      </div>

      {/* Filter / Search Card */}
      <section className="booking-filter-card" aria-label="Bộ lọc tìm kiếm phòng">
        <div className="filter-form-grid">
          <Field
            label="Ngày nhận phòng"
            type="date"
            min={getTodayDateString()}
            value={checkInDate}
            onChange={(e) => handleCheckInChange(e.target.value)}
            hint="Nhận phòng từ 12:00 trưa"
            required
          />

          <Field
            label="Ngày trả phòng"
            type="date"
            min={getTomorrowDateString(checkInDate)}
            value={checkOutDate}
            onChange={(e) => handleCheckOutChange(e.target.value)}
            hint="Trả phòng trước 10:00 sáng"
            required
          />

          <Field
            label="Số lượng khách"
            type="number"
            min="1"
            max="10"
            value={guestCount}
            onChange={(e) => setGuestCount(Math.max(1, Number(e.target.value) || 1))}
            hint={`Phù hợp tối đa ${selectedRoom.maxOccupancy} người`}
            required
          />

          <div className="filter-info-box">
            <Icon name="calendar" size={18} />
            <div>
              <span>Khoảng lưu trú: </span>
              <strong>{quote.nights} đêm</strong>
              <div className="small muted">Khoảng thời gian [12:00 - 10:00)</div>
            </div>
          </div>
        </div>
      </section>

      {/* Main Grid: Room Selection (Left) + Quote Breakdown (Right) */}
      <div className="booking-main-grid">
        {/* Left Column: Room Selection */}
        <section className="room-selection-col" aria-label="Danh mục loại phòng">
          <div className="room-section-header">
            <h2>Chọn loại phòng</h2>
            <Badge tone="neutral">
              {loadingRooms ? 'Đang cập nhật…' : `${roomTypes.length} loại phòng khả dụng`}
            </Badge>
          </div>

          <div className="room-selection-list">
            {roomTypes.map((room) => {
              const isSelected = room.id === selectedRoomId
              const iconTone =
                room.code.includes('OCEAN') || room.code.includes('DELUXE')
                  ? 'teal'
                  : room.code.includes('PRESIDENT') || room.code.includes('SUITE')
                    ? 'amber'
                    : 'violet'

              return (
                <div
                  key={room.id}
                  className={`room-card ${isSelected ? 'selected' : ''}`}
                  onClick={() => setSelectedRoomId(room.id)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(e) => e.key === 'Enter' && setSelectedRoomId(room.id)}
                  aria-pressed={isSelected}
                >
                  <div className={`room-card-icon ${iconTone}`}>
                    <Icon name={room.code.includes('SUITE') ? 'shield' : 'bed'} size={24} />
                  </div>

                  <div className="room-card-body">
                    <div className="room-card-header">
                      <div>
                        <h3>{room.name}</h3>
                        <span style={{ display: 'inline-block', marginTop: '4px' }}>
                          <Badge tone="neutral">Mã: {room.code}</Badge>
                        </span>
                      </div>
                      <div className="room-card-price">
                        <strong>{formatVnd(room.basePricePerNight)}</strong>
                        <small>/ đêm</small>
                      </div>
                    </div>

                    <p className="room-card-desc">
                      {room.description ?? 'Không gian nghỉ dưỡng tiện nghi, thoải mái.'}
                    </p>

                    <div className="room-card-footer">
                      <div className="badge-row">
                        <Badge tone="neutral">Tối đa {room.maxOccupancy} khách</Badge>
                        <Badge tone="success">Sẵn sàng</Badge>
                      </div>

                      <div className="room-card-radio">
                        <span className="radio-circle" />
                        <span>{isSelected ? 'Đang chọn' : 'Chọn phòng'}</span>
                      </div>
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        </section>

        {/* Right Column: Sticky Quote Breakdown Card */}
        <aside className="quote-col" aria-label="Báo giá chi tiết">
          <Card className="quote-card">
            <div className="card-heading">
              <div>
                <p className="eyebrow">TẠM TÍNH CHI PHÍ</p>
                <h2>Chi tiết báo giá</h2>
              </div>
              <Icon name="key" size={22} />
            </div>

            {/* Selected Room Details */}
            <div className="quote-room-badge">
              <Icon name="bed" size={20} />
              <div>
                <strong>{selectedRoom.name}</strong>
                <small>
                  {formatDate(checkInDate)} (12:00) → {formatDate(checkOutDate)} (10:00)
                </small>
              </div>
            </div>

            {/* Price Rows Breakdown */}
            <div className="quote-rows">
              <div className="quote-row">
                <span className="muted">Thời gian lưu trú</span>
                <strong>{quote.nights} đêm</strong>
              </div>

              <div className="quote-row">
                <span className="muted">Số lượng khách</span>
                <strong>{guestCount} người</strong>
              </div>

              <div className="quote-row">
                <span className="muted">Giá cơ sở</span>
                <span>{formatVnd(selectedRoom.basePricePerNight)} / đêm</span>
              </div>

              <div className="quote-row">
                <span className="muted">Tổng tiền phòng gốc</span>
                <strong>{formatVnd(quote.baseAmount)}</strong>
              </div>

              {/* Tier Selection Preview */}
              <div className="quote-row">
                <span className="muted">Hạng hội viên</span>
                <div style={{ display: 'flex', gap: '4px' }}>
                  {(['STANDARD', 'VIP', 'DIAMOND'] as const).map((t) => (
                    <button
                      key={t}
                      type="button"
                      className={`badge ${selectedTier === t ? 'badge--success' : ''}`}
                      style={{
                        cursor: 'pointer',
                        border: selectedTier === t ? '1px solid #358166' : '1px solid transparent',
                      }}
                      onClick={() => setSelectedTier(t)}
                      title={`Xem chiết khấu hạng ${t}`}
                    >
                      {t}
                    </button>
                  ))}
                </div>
              </div>

              {quote.discountAmount > 0 && (
                <div className="quote-row discount">
                  <span>Ưu đãi thành viên ({quote.discountRate * 100}%)</span>
                  <span>-{formatVnd(quote.discountAmount)}</span>
                </div>
              )}
            </div>

            {/* Total Payable */}
            <div className="quote-total">
              <span>Tổng tạm tính</span>
              <strong>{formatVnd(quote.payableAmount)}</strong>
            </div>

            {/* Member savings notice */}
            {selectedTier !== 'STANDARD' ? (
              <div className="quote-member-note">
                <Icon name="check" size={16} />
                <span>Bạn tiết kiệm được {formatVnd(quote.discountAmount)} với hạng {selectedTier}!</span>
              </div>
            ) : (
              <div className="quote-member-note" style={{ background: '#f5f7f8', color: '#687b80' }}>
                <Icon name="info" size={16} />
                <span>Nâng hạng VIP hoặc DIAMOND để nhận ưu đãi lên đến 15%.</span>
              </div>
            )}

            {/* Occupancy Warning */}
            {exceedsOccupancy && (
              <Alert tone="danger">
                Số lượng khách ({guestCount}) vượt quá sức chứa tối đa của loại phòng này ({selectedRoom.maxOccupancy} người). Vui lòng chọn loại phòng lớn hơn hoặc giảm số khách.
              </Alert>
            )}

            {isInvalidDates && (
              <Alert tone="danger">
                Ngày trả phòng phải sau ngày nhận phòng ít nhất 1 đêm.
              </Alert>
            )}

            {/* Action Buttons */}
            <div className="quote-actions">
              {user ? (
                <Button
                  variant="primary"
                  className="button--full"
                  disabled={exceedsOccupancy || isInvalidDates}
                  onClick={() => {
                    // Previews step 2 readiness
                    alert(
                      `Đã ghi nhận thông tin đặt phòng:\n- Loại phòng: ${selectedRoom.name}\n- Thời gian: ${checkInDate} đến ${checkOutDate} (${quote.nights} đêm)\n- Số khách: ${guestCount}\n- Tổng tiền: ${formatVnd(quote.payableAmount)}\n\nSẵn sàng bước sang Giai đoạn 3 (Giữ chỗ 15 phút & Thanh toán Demo)!`
                    )
                  }}
                >
                  <Icon name="calendar" size={17} />
                  Tiếp tục giữ phòng (Hold 15p)
                </Button>
              ) : (
                <Link
                  to="/login"
                  className="button button--primary button--full"
                >
                  <Icon name="user" size={17} />
                  Đăng nhập để đặt phòng
                </Link>
              )}
            </div>

            <p className="quote-guarantee-note">
              {user
                ? '⚡ Khi nhấn Giữ phòng, hệ thống tự động gán phòng trống và giữ chỗ trong 15 phút mà không trừ tiền ngay.'
                : 'Bạn có thể xem trước báo giá. Vui lòng đăng nhập tài khoản để xác nhận giữ chỗ.'}
            </p>
          </Card>
        </aside>
      </div>
    </div>
  )
}
