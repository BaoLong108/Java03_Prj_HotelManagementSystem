import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../../auth/AuthProvider'
import { ApiError, errorMessage } from '../../../shared/api/client'
import { Alert, Button, Card, EmptyState, LoadingState, PageHeader } from '../../../shared/ui'
import { FixtureNotice, USE_FIXTURES } from '../shared/fixtureMode'
import { addHotelDays, calendarRange, formatHotelTime, hotelDate, isHotelDate } from '../shared/staffTime'
import { getRoomCalendar } from './roomCalendarApi'
import { visibleCalendarEntries } from './calendarGeometry'
import { entryLabel, RoomCalendarGrid } from './RoomCalendarGrid'
import type { CalendarSelection } from './RoomCalendarGrid'
import type { RoomCalendar } from './roomCalendarTypes'
import './roomCalendar.css'

type LoadState = { key: string; data?: RoomCalendar; error?: string }
export function RoomCalendarPage() {
  const { user } = useAuth()
  const canReadRooms = Boolean(user?.roles.some(role => role === 'ADMIN' || role === 'STAFF'))
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const requestedDate = params.get('from')
  const startDate = requestedDate && isHotelDate(requestedDate) ? requestedDate : hotelDate()
  const rawType = params.get('roomTypeId') ?? ''
  const roomTypeId = /^\d+$/.test(rawType) && Number.isSafeInteger(Number(rawType)) && Number(rawType) > 0 ? Number(rawType) : undefined
  const { from, to } = calendarRange(startDate)
  const [reload, setReload] = useState(0)
  const key = `${user?.id}|${canReadRooms}|${from}|${to}|${roomTypeId ?? ''}|${reload}`
  const [state, setState] = useState<LoadState>({ key: '' })
  const [roomTypes, setRoomTypes] = useState<{ id: number; name: string }[]>([])
  const [selection, setSelection] = useState<CalendarSelection | null>(null)
  const details = useRef<HTMLElement>(null)
  const current = state.key === key ? state : { key }

  useEffect(() => {
    if (!canReadRooms) return
    const controller = new AbortController()
    setState({ key }); setSelection(null)
    getRoomCalendar({ from, to, roomTypeId }, controller.signal).then(data => {
      if (controller.signal.aborted) return
      setState({ key, data })
      setRoomTypes(previous => {
        const types = new Map((data.roomTypes ?? previous).map(type => [type.id, type.name]))
        data.rooms.forEach(room => types.set(room.roomTypeId, room.roomTypeName))
        return [...types].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name, 'vi'))
      })
    }).catch(error => {
      if (controller.signal.aborted) return
      if (error instanceof ApiError && error.status === 403) { navigate('/403', { replace: true }); return }
      setState({ key, error: errorMessage(error) })
    })
    return () => controller.abort()
  }, [from, to, roomTypeId, key, navigate, canReadRooms])

  useEffect(() => { if (selection) details.current?.focus() }, [selection])

  function update(date: string, type = rawType) {
    const next = new URLSearchParams(params)
    next.set('from', date)
    if (type) next.set('roomTypeId', type); else next.delete('roomTypeId')
    setParams(next)
  }
  return <div className="staff-calendar-page">
    <PageHeader eyebrow="KHÔNG GIAN NHÂN VIÊN" title="Lịch phòng" description="Theo dõi lịch đặt và khoảng chặn theo từng phòng vật lý." />
    <FixtureNotice />

    {!canReadRooms && <Card><EmptyState icon="calendar" title="Không có quyền xem lịch phòng"
      description="Vui lòng đăng nhập bằng tài khoản Staff hoặc Admin." /></Card>}
    {USE_FIXTURES && <p className="fixture-help">Bộ mẫu nằm trong tháng 10/2026. <button type="button" className="text-link" onClick={() => update('2026-10-08', '')}>Xem tuần mẫu 08/10/2026</button></p>}
    {requestedDate && !isHotelDate(requestedDate) && <Alert tone="warning">Ngày trên đường dẫn không hợp lệ. Đang hiển thị từ hôm nay.</Alert>}
    {canReadRooms && <Card className="calendar-controls"><div className="calendar-toolbar">
      <div className="calendar-week-buttons"><Button variant="secondary" onClick={() => update(addHotelDays(startDate, -7))}>Tuần trước</Button>
        <Button variant="secondary" onClick={() => update(hotelDate())}>Hôm nay</Button>
        <Button variant="secondary" onClick={() => update(addHotelDays(startDate, 7))}>Tuần sau</Button></div>
      <label className="staff-control">Ngày bắt đầu<input type="date" value={startDate} onChange={event => { if (isHotelDate(event.target.value)) update(event.target.value) }} /></label>
      <label className="staff-control">Loại phòng<select aria-label="Loại phòng" value={roomTypeId ?? ''} onChange={event => update(startDate, event.target.value)}>
        <option value="">Tất cả loại phòng</option>
        {roomTypeId && !roomTypes.some(type => type.id === roomTypeId) && <option value={roomTypeId}>Loại phòng #{roomTypeId}</option>}
        {roomTypes.map(type => <option value={type.id} key={type.id}>{type.name}</option>)}
      </select></label>
      <Button variant="secondary" onClick={() => setReload(value => value + 1)}>Tải lại</Button>
    </div><p className="calendar-range">{formatHotelTime(from)} – {formatHotelTime(to)} · Giờ Việt Nam (UTC+07:00)</p></Card>}
    <ul className="calendar-legend" aria-label="Chú thích lịch phòng">
      <li className="legend-confirmed">Đã xác nhận</li><li className="legend-completed">Hoàn tất</li>
      <li className="legend-hold">Đang giữ chỗ</li><li className="legend-block">Chặn</li>
    </ul>
    <p className="calendar-note">Mặc định nhận phòng 12:00, trả phòng 10:00; hai lượt kề nhau cách 2 giờ. Khoảng trống trên lưới không xác nhận phòng còn trống.</p>
    {canReadRooms && !current.data && !current.error && <Card><LoadingState message="Đang tải lịch phòng…" /></Card>}
    {current.error && <Alert>{current.error}</Alert>}
    {current.data?.rooms.some(room => room.blockError) && <Alert tone="warning">Một số phòng chưa tải được khoảng chặn; các hàng này chưa có dữ liệu xác thực. Hãy tải lại.</Alert>}
    {current.data?.rooms.length !== 0 && current.data && !current.data.rooms.some(room => room.blockError || visibleCalendarEntries(room.entries, from, to).length)
      && <p className="calendar-note" role="status">Không có booking hoặc khoảng chặn trong phạm vi đang xem.</p>}
    {current.data && (current.data.rooms.length ? <RoomCalendarGrid calendar={current.data} onSelect={setSelection} />
      : <Card><EmptyState icon="calendar" title="Không có phòng trong kết quả" description="Máy chủ không trả phòng cho bộ lọc này. Hãy chọn loại phòng hoặc khoảng thời gian khác." /></Card>)}
    {selection && current.data && <section ref={details} tabIndex={-1} className="card calendar-details" aria-label="Chi tiết khoảng lịch">
      <div className="calendar-detail-heading"><h2>{selection.entry.kind === 'BLOCK' ? 'Chi tiết khoảng chặn' : selection.entry.bookingCode}</h2>
        <Button variant="ghost" onClick={() => setSelection(null)}>Đóng chi tiết</Button></div>
      <dl><div><dt>Phòng</dt><dd>{selection.room.roomNumber} · {selection.room.roomTypeName}</dd></div>
        <div><dt>Trạng thái</dt><dd>{entryLabel(selection.entry)}</dd></div>
        <div><dt>Bắt đầu</dt><dd>{formatHotelTime(selection.entry.startAt)}</dd></div>
        <div><dt>Kết thúc</dt><dd>{formatHotelTime(selection.entry.endAt)}</dd></div>
        {selection.entry.kind === 'BLOCK' ? <><div><dt>Lý do</dt><dd>{selection.entry.reason}</dd></div>
          {selection.entry.createdByName && <div><dt>Người tạo</dt><dd>{selection.entry.createdByName}</dd></div>}</>
          : selection.entry.status === 'PENDING_PAYMENT' && <div><dt>Hạn giữ chỗ</dt><dd>{selection.entry.holdExpiresAt ? formatHotelTime(selection.entry.holdExpiresAt) : 'Máy chủ chưa cung cấp'}</dd></div>}
      </dl>
    </section>}
  </div>
}
