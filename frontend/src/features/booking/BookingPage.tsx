import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import { bookingApi, calculateQuote } from './api'
import {
  DEFAULT_ROOM_TYPES,
  bookingStatusLabels,
  bookingStatusTones,
  formatDate,
  formatDateTime,
  formatVnd,
  getTodayDateString,
  getTomorrowDateString,
  toCheckInIso,
  toCheckOutIso,
} from './types'
import type { BookingResponse, RoomType } from './types'
import { errorMessage } from '../../shared/api/client'
import { Alert, Badge, Button, Card, EmptyState, Field, PageHeader } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'

type Step = 'SEARCH' | 'HOLD' | 'CONFIRMED'
type ActiveTab = 'BOOKING' | 'MY_BOOKINGS'

export function BookingPage() {
  const { user } = useAuth()

  // Navigation Tabs: 'BOOKING' | 'MY_BOOKINGS'
  const [activeTab, setActiveTab] = useState<ActiveTab>('BOOKING')

  // Booking Flow Steps: 'SEARCH' (1) -> 'HOLD' (2) -> 'CONFIRMED' (3)
  const [step, setStep] = useState<Step>('SEARCH')

  // Search & Filter State
  const [checkInDate, setCheckInDate] = useState(() => getTodayDateString())
  const [checkOutDate, setCheckOutDate] = useState(() => getTomorrowDateString(getTodayDateString()))
  const [guestCount, setGuestCount] = useState<number>(2)

  // Room Catalog
  const [roomTypes, setRoomTypes] = useState<RoomType[]>(DEFAULT_ROOM_TYPES)
  const [selectedRoomId, setSelectedRoomId] = useState<number>(DEFAULT_ROOM_TYPES[0].id)
  const [loadingRooms, setLoadingRooms] = useState(false)

  // Member Tier Simulation
  const [selectedTier, setSelectedTier] = useState<'STANDARD' | 'VIP' | 'DIAMOND'>('STANDARD')

  // Active Booking & Async State
  const [currentBooking, setCurrentBooking] = useState<BookingResponse | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  // My Bookings State
  const [myBookings, setMyBookings] = useState<BookingResponse[]>([])
  const [loadingMyBookings, setLoadingMyBookings] = useState(false)

  // Countdown Timer State for Hold (15 minutes)
  const [remainingSeconds, setRemainingSeconds] = useState<number>(0)

  // Load Room Types on Mount
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

  // Load My Bookings when switching to tab or after state change
  const fetchMyBookings = () => {
    if (!user) return
    setLoadingMyBookings(true)
    bookingApi
      .getMyBookings()
      .then((list) => setMyBookings(list))
      .catch(() => {})
      .finally(() => setLoadingMyBookings(false))
  }

  useEffect(() => {
    if (activeTab === 'MY_BOOKINGS' && user) {
      fetchMyBookings()
    }
  }, [activeTab, user])

  // Timer logic for HOLD step
  useEffect(() => {
    if (step !== 'HOLD' || !currentBooking?.holdExpiresAt) {
      setRemainingSeconds(0)
      return
    }

    const updateTimer = () => {
      const expiry = new Date(currentBooking.holdExpiresAt).getTime()
      const now = Date.now()
      const diffSec = Math.max(0, Math.floor((expiry - now) / 1000))
      setRemainingSeconds(diffSec)

      if (diffSec <= 0) {
        setCurrentBooking((prev) => (prev ? { ...prev, status: 'EXPIRED' } : null))
      }
    }

    updateTimer()
    const interval = setInterval(updateTimer, 1000)
    return () => clearInterval(interval)
  }, [step, currentBooking?.holdExpiresAt])

  // Selected Room & Quote
  const selectedRoom = useMemo(() => {
    return roomTypes.find((r) => r.id === selectedRoomId) ?? roomTypes[0]
  }, [roomTypes, selectedRoomId])

  const quote = useMemo(() => {
    return calculateQuote(selectedRoom, checkInDate, checkOutDate, guestCount, selectedTier)
  }, [selectedRoom, checkInDate, checkOutDate, guestCount, selectedTier])

  // Date Change Handlers
  function handleCheckInChange(newCheckIn: string) {
    setCheckInDate(newCheckIn)
    if (newCheckIn >= checkOutDate) {
      setCheckOutDate(getTomorrowDateString(newCheckIn))
    }
  }

  function handleCheckOutChange(newCheckOut: string) {
    if (newCheckOut > checkInDate) {
      setCheckOutDate(newCheckOut)
    }
  }

  // Action: Step 1 -> Step 2 (Create Hold)
  async function handleCreateHold() {
    if (!user) return
    setBusy(true)
    setActionError(null)
    try {
      const response = await bookingApi.create({
        roomTypeId: selectedRoom.id,
        guestCount,
        startAt: toCheckInIso(checkInDate),
        endAt: toCheckOutIso(checkOutDate),
      })
      setCurrentBooking(response)
      setStep('HOLD')
      fetchMyBookings()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  // Action: Step 2 -> Step 3 (Confirm Payment)
  async function handleConfirmPayment(bookingCode: string) {
    setBusy(true)
    setActionError(null)
    try {
      const response = await bookingApi.confirm(bookingCode)
      setCurrentBooking(response)
      setStep('CONFIRMED')
      fetchMyBookings()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  // Action: Cancel Booking (from Hold or from My Bookings list)
  async function handleCancelBooking(bookingCode: string) {
    setBusy(true)
    setActionError(null)
    try {
      const response = await bookingApi.cancel(bookingCode)
      if (currentBooking?.bookingCode === bookingCode) {
        setCurrentBooking(response)
      }
      fetchMyBookings()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  // Resume hold payment from My Bookings table
  function handleResumeHold(booking: BookingResponse) {
    setCurrentBooking(booking)
    setStep('HOLD')
    setActiveTab('BOOKING')
  }

  // Formatted countdown timer display (MM:SS)
  const timerMinutes = Math.floor(remainingSeconds / 60)
  const timerSeconds = remainingSeconds % 60
  const formattedCountdown = `${String(timerMinutes).padStart(2, '0')}:${String(timerSeconds).padStart(2, '0')}`
  const isUrgent = remainingSeconds > 0 && remainingSeconds <= 180 // < 3 mins
  const isExpired = remainingSeconds <= 0 && step === 'HOLD'
  const progressPercent = Math.min(100, Math.max(0, (remainingSeconds / (15 * 60)) * 100))

  // Occupancy & Date validation
  const exceedsOccupancy = guestCount > selectedRoom.maxOccupancy
  const isInvalidDates = checkOutDate <= checkInDate

  return (
    <div className="booking-container">
      <PageHeader
        eyebrow="KỲ NGHỈ CỦA BẠN"
        title="Tìm và đặt phòng"
        description="Lựa chọn phòng nghỉ, giữ chỗ tạm thời trong 15 phút và thanh toán an toàn."
      />

      {/* Main Tabs */}
      <div className="booking-tabs" role="tablist" aria-label="Phân loại chức năng đặt phòng">
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'BOOKING'}
          className={`booking-tab-btn ${activeTab === 'BOOKING' ? 'active' : ''}`}
          onClick={() => setActiveTab('BOOKING')}
        >
          <Icon name="calendar" size={17} />
          <span>Đặt phòng mới</span>
        </button>

        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'MY_BOOKINGS'}
          className={`booking-tab-btn ${activeTab === 'MY_BOOKINGS' ? 'active' : ''}`}
          onClick={() => {
            setActiveTab('MY_BOOKINGS')
            fetchMyBookings()
          }}
        >
          <Icon name="bed" size={17} />
          <span>Đơn đặt phòng của tôi</span>
          {myBookings.length > 0 && (
            <span style={{ marginLeft: '4px' }}>
              <Badge tone="success">{myBookings.length}</Badge>
            </span>
          )}
        </button>
      </div>

      {/* Global Error Banner */}
      {actionError && (
        <Alert tone="danger">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span>{actionError}</span>
            <Button variant="ghost" onClick={() => setActionError(null)}>
              Đóng
            </Button>
          </div>
        </Alert>
      )}

      {/* TAB 1: BOOKING FLOW */}
      {activeTab === 'BOOKING' && (
        <>
          {/* Stepper */}
          <div className="booking-steps" aria-label="Các bước đặt phòng">
            <span className={step === 'SEARCH' ? 'current' : 'completed'}>
              <b>{step === 'SEARCH' ? '1' : '✓'}</b>
              Chọn phòng & Báo giá
            </span>
            <i />
            <span
              className={
                step === 'HOLD' ? 'current' : step === 'CONFIRMED' ? 'completed' : ''
              }
            >
              <b>{step === 'CONFIRMED' ? '✓' : '2'}</b>
              Xác nhận giữ chỗ (15p)
            </span>
            <i />
            <span className={step === 'CONFIRMED' ? 'current' : ''}>
              <b>3</b>
              Thanh toán Demo
            </span>
          </div>

          {/* STEP 1: AVAILABILITY SEARCH & QUOTE */}
          {step === 'SEARCH' && (
            <>
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

              {/* Main Grid: Room Selection + Quote Breakdown */}
              <div className="booking-main-grid">
                {/* Left Column: Room Selection */}
                <section className="room-selection-col" aria-label="Danh mục loại phòng">
                  <div className="room-section-header">
                    <h2>Chọn loại phòng</h2>
                    <Badge tone="neutral">
                      {loadingRooms ? 'Đang tải…' : `${roomTypes.length} loại phòng khả dụng`}
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
                                border:
                                  selectedTier === t ? '1px solid #358166' : '1px solid transparent',
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
                        <span>
                          Bạn tiết kiệm được {formatVnd(quote.discountAmount)} với hạng {selectedTier}!
                        </span>
                      </div>
                    ) : (
                      <div
                        className="quote-member-note"
                        style={{ background: '#f5f7f8', color: '#687b80' }}
                      >
                        <Icon name="info" size={16} />
                        <span>Nâng hạng VIP hoặc DIAMOND để nhận ưu đãi lên đến 15%.</span>
                      </div>
                    )}

                    {/* Occupancy & Date Warnings */}
                    {exceedsOccupancy && (
                      <Alert tone="danger">
                        Số lượng khách ({guestCount}) vượt quá sức chứa tối đa của loại phòng này (
                        {selectedRoom.maxOccupancy} người).
                      </Alert>
                    )}

                    {isInvalidDates && (
                      <Alert tone="danger">Ngày trả phòng phải sau ngày nhận phòng ít nhất 1 đêm.</Alert>
                    )}

                    {/* Action Buttons */}
                    <div className="quote-actions">
                      {user ? (
                        <Button
                          variant="primary"
                          className="button--full"
                          disabled={exceedsOccupancy || isInvalidDates}
                          busy={busy}
                          onClick={handleCreateHold}
                        >
                          <Icon name="calendar" size={17} />
                          Tiến hành giữ phòng (Hold 15p)
                        </Button>
                      ) : (
                        <Link to="/login" className="button button--primary button--full">
                          <Icon name="user" size={17} />
                          Đăng nhập để đặt phòng
                        </Link>
                      )}
                    </div>

                    <p className="quote-guarantee-note">
                      {user
                        ? '⚡ Hệ thống sẽ tự động gán một phòng trống và giữ chỗ trong 15 phút mà không trừ tiền ngay.'
                        : 'Bạn có thể xem trước báo giá. Vui lòng đăng nhập tài khoản để xác nhận giữ chỗ.'}
                    </p>
                  </Card>
                </aside>
              </div>
            </>
          )}

          {/* STEP 2: HOLD CONFIRMATION & 15-MINUTE COUNTDOWN */}
          {step === 'HOLD' && currentBooking && (
            <Card className="hold-card">
              <div className="hold-header">
                <Badge tone="warning">ĐANG GIỮ CHỖ TẠM THỜI</Badge>
                <h2>Đơn đặt phòng #{currentBooking.bookingCode}</h2>
                <p className="muted">
                  Phòng của bạn đang được hệ thống giữ chỗ độc quyền trong 15 phút.
                </p>
              </div>

              {/* Countdown Timer Display */}
              <div
                className={`hold-timer-box ${isUrgent ? 'urgent' : ''} ${isExpired ? 'expired' : ''}`}
              >
                <span className="small muted">THỜI GIAN GIỮ PHÒNG CÒN LẠI</span>
                <div className="hold-timer-digits">
                  {isExpired ? '00:00' : formattedCountdown}
                </div>
                <div className="hold-progress-track">
                  <div
                    className="hold-progress-bar"
                    style={{ width: `${isExpired ? 0 : progressPercent}%` }}
                  />
                </div>
                <span className="small" style={{ marginTop: '8px' }}>
                  {isExpired
                    ? 'Thời gian giữ phòng đã kết thúc.'
                    : isUrgent
                      ? '⚠️ Sắp hết hạn giữ chỗ! Vui lòng hoàn tất thanh toán.'
                      : 'Hạn giữ phòng: 15 phút kể từ lúc đặt'}
                </span>
              </div>

              {/* Booking Details Summary */}
              <div className="booking-details-box">
                <dl className="booking-details-grid">
                  <div>
                    <dt>Mã đơn đặt phòng</dt>
                    <dd>{currentBooking.bookingCode}</dd>
                  </div>
                  <div>
                    <dt>Trạng thái</dt>
                    <dd>
                      <Badge tone={bookingStatusTones[currentBooking.status]}>
                        {bookingStatusLabels[currentBooking.status]}
                      </Badge>
                    </dd>
                  </div>
                  <div>
                    <dt>Thời gian nhận phòng</dt>
                    <dd>{formatDateTime(currentBooking.startAt)} (12:00)</dd>
                  </div>
                  <div>
                    <dt>Thời gian trả phòng</dt>
                    <dd>{formatDateTime(currentBooking.endAt)} (10:00)</dd>
                  </div>
                  <div>
                    <dt>Số lượng khách</dt>
                    <dd>{currentBooking.guestCount} người</dd>
                  </div>
                  <div>
                    <dt>Hạn giữ chỗ đến</dt>
                    <dd>{formatDateTime(currentBooking.holdExpiresAt)}</dd>
                  </div>
                  <div>
                    <dt>Tiền phòng gốc</dt>
                    <dd>{formatVnd(currentBooking.baseAmount)}</dd>
                  </div>
                  <div>
                    <dt>Ưu đãi giảm giá</dt>
                    <dd style={{ color: '#2e7d32' }}>
                      -{formatVnd(currentBooking.discountAmount)} (
                      {currentBooking.discountRate * 100}%)
                    </dd>
                  </div>
                  <div style={{ gridColumn: 'span 2', borderTop: '1px solid #e1e9e5', paddingTop: '10px' }}>
                    <dt>Tổng tiền cần thanh toán</dt>
                    <dd style={{ fontSize: '18px', color: 'var(--primary)' }}>
                      {formatVnd(currentBooking.payableAmount)}
                    </dd>
                  </div>
                </dl>
              </div>

              {/* Action Buttons */}
              {isExpired ? (
                <div style={{ textAlign: 'center' }}>
                  <Alert tone="danger">
                    Đơn đặt phòng này đã hết hạn 15 phút. Phòng vật lý đã được giải phóng tự động để phục vụ khách hàng khác.
                  </Alert>
                  <Button
                    variant="primary"
                    onClick={() => {
                      setCurrentBooking(null)
                      setStep('SEARCH')
                    }}
                  >
                    <Icon name="calendar" size={17} />
                    Đặt phòng mới
                  </Button>
                </div>
              ) : currentBooking.status === 'CANCELLED' ? (
                <div style={{ textAlign: 'center' }}>
                  <Alert tone="neutral">Đơn đặt phòng này đã được hủy thành công.</Alert>
                  <Button
                    variant="primary"
                    onClick={() => {
                      setCurrentBooking(null)
                      setStep('SEARCH')
                    }}
                  >
                    Đặt phòng khác
                  </Button>
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                  <Button
                    variant="primary"
                    className="button--full"
                    busy={busy}
                    onClick={() => handleConfirmPayment(currentBooking.bookingCode)}
                  >
                    <Icon name="check" size={18} />
                    Thanh toán Demo ngay ({formatVnd(currentBooking.payableAmount)})
                  </Button>

                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <Button
                      variant="ghost"
                      busy={busy}
                      onClick={() => handleCancelBooking(currentBooking.bookingCode)}
                      style={{ color: '#b25d53' }}
                    >
                      <Icon name="close" size={16} />
                      Hủy giữ chỗ
                    </Button>

                    <Button
                      variant="ghost"
                      onClick={() => {
                        setCurrentBooking(null)
                        setStep('SEARCH')
                      }}
                    >
                      Chọn lại loại phòng
                    </Button>
                  </div>
                </div>
              )}
            </Card>
          )}

          {/* STEP 3: PAYMENT CONFIRMED / COMPLETED */}
          {step === 'CONFIRMED' && currentBooking && (
            <Card className="booking-success-card">
              <div className="success-icon-badge">
                <Icon name="check" size={36} />
              </div>

              <div style={{ marginBottom: '12px' }}>
                <Badge tone="success">THANH TOÁN THÀNH CÔNG</Badge>
              </div>
              <h2>Đặt phòng đã được xác nhận!</h2>
              <p className="muted" style={{ maxWidth: '480px', margin: '0 auto 24px' }}>
                Cảm ơn bạn! Phòng của bạn đã được đảm bảo chắc chắn cho kỳ nghỉ sắp tới. Thông tin chi tiết đã được lưu vào hệ thống.
              </p>

              <div className="booking-details-box" style={{ textAlign: 'left' }}>
                <dl className="booking-details-grid">
                  <div>
                    <dt>Mã đặt phòng</dt>
                    <dd style={{ fontSize: '15px', color: 'var(--primary)' }}>
                      {currentBooking.bookingCode}
                    </dd>
                  </div>
                  <div>
                    <dt>Trạng thái</dt>
                    <dd>
                      <Badge tone="success">CONFIRMED · Đã xác nhận</Badge>
                    </dd>
                  </div>
                  <div>
                    <dt>Ngày nhận phòng</dt>
                    <dd>{formatDateTime(currentBooking.startAt)} (12:00)</dd>
                  </div>
                  <div>
                    <dt>Ngày trả phòng</dt>
                    <dd>{formatDateTime(currentBooking.endAt)} (10:00)</dd>
                  </div>
                  <div>
                    <dt>Số khách</dt>
                    <dd>{currentBooking.guestCount} người</dd>
                  </div>
                  <div>
                    <dt>Tổng tiền đã thanh toán</dt>
                    <dd style={{ color: 'var(--primary)', fontSize: '15px' }}>
                      {formatVnd(currentBooking.payableAmount)}
                    </dd>
                  </div>
                </dl>
              </div>

              <div className="button-row" style={{ justifyContent: 'center', marginTop: '24px' }}>
                <Button
                  variant="primary"
                  onClick={() => {
                    setActiveTab('MY_BOOKINGS')
                    fetchMyBookings()
                  }}
                >
                  <Icon name="bed" size={17} />
                  Xem đơn đặt phòng của tôi
                </Button>

                <Button
                  variant="secondary"
                  onClick={() => {
                    setCurrentBooking(null)
                    setStep('SEARCH')
                  }}
                >
                  Đặt thêm phòng khác
                </Button>
              </div>
            </Card>
          )}
        </>
      )}

      {/* TAB 2: MY BOOKINGS LIST */}
      {activeTab === 'MY_BOOKINGS' && (
        <Card>
          <div className="card-heading">
            <div>
              <p className="eyebrow">LỊCH SỬ GIAO DỊCH</p>
              <h2>Đơn đặt phòng của tôi</h2>
            </div>
            <Button variant="ghost" busy={loadingMyBookings} onClick={fetchMyBookings}>
              <Icon name="refresh" size={16} />
              Làm mới
            </Button>
          </div>

          {!user ? (
            <EmptyState
              icon="user"
              title="Vui lòng đăng nhập"
              description="Đăng nhập tài khoản để xem toàn bộ lịch sử đơn đặt phòng và trạng thái thanh toán."
              action={
                <Link to="/login" className="button button--primary">
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
                <Button variant="primary" onClick={() => setActiveTab('BOOKING')}>
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
                              Đã giảm {formatVnd(b.discountAmount)}
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
                            {isPending && (
                              <>
                                <Button
                                  variant="primary"
                                  className="button--small"
                                  onClick={() => handleResumeHold(b)}
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
                                <Icon name="check" size={14} /> Sẵn sàng nhận phòng
                              </Badge>
                            )}

                            {b.status === 'COMPLETED' && (
                              <span className="small muted">Đã hoàn thành</span>
                            )}

                            {(b.status === 'CANCELLED' || b.status === 'EXPIRED') && (
                              <Button
                                variant="ghost"
                                className="button--small"
                                onClick={() => {
                                  setSelectedRoomId(b.roomTypeId)
                                  setActiveTab('BOOKING')
                                  setStep('SEARCH')
                                }}
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
      )}
    </div>
  )
}
