import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { Alert, Button, Card, DataTable, LoadingState, Tabs } from '../../../shared/ui'
import { ApiError, errorMessage } from '../../../shared/api/client'
import { getRealRoomCalendar } from '../calendar/roomCalendarApi'
import { addHotelDays, calendarRange, formatHotelTime, hotelDate } from '../shared/staffTime'
import { bookingStatuses, statusLabels } from '../shared/bookingStatus'
import { BookingSearchPage } from './BookingSearchPage'
import { dailyFilters, dailyQuery, getDaily } from './dailyApi'
import type { DailyBooking } from './dailyApi'
import { formatVnd } from './bookingSearchFormat'
import '../shared/operations.css'

export function StaffBookings() {
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') === 'daily' ? 'daily' : 'search'
  return <div className="staff-bookings-tabs"><Tabs label="Cách xem booking" value={tab} items={[{ value: 'search', label: 'Tra cứu' }, { value: 'daily', label: 'Theo ngày' }]} onChange={value => { const next = new URLSearchParams(params); next.set('tab', value); setParams(next) }} />
    {tab === 'daily' ? <DailyBookings /> : <BookingSearchPage />}
  </div>
}
function DailyBookings() {
  const [params, setParams] = useSearchParams(), navigate = useNavigate()
  const filters = dailyFilters(params)
  const [reload, setReload] = useState(0)
  const key = `${params.toString()}|${reload}`
  const [state, setState] = useState<{ key: string; items?: DailyBooking[]; truncated?: boolean; error?: string }>({ key: '' })
  const [types, setTypes] = useState<{ id: number; name: string }[]>([]), [typesError, setTypesError] = useState('')
  const current = state.key === key ? state : { key }
  useEffect(() => {
    const controller = new AbortController()
    getRealRoomCalendar(calendarRange(hotelDate()), controller.signal).then(data => { if (!controller.signal.aborted) setTypes(data.roomTypes ?? []) }).catch(e => { if (!controller.signal.aborted) setTypesError(errorMessage(e)) })
    return () => controller.abort()
  }, [])
  const { date, roomTypeId, status } = filters
  useEffect(() => {
    const controller = new AbortController()
    getDaily({ date, roomTypeId, status }, controller.signal).then(data => { if (!controller.signal.aborted) setState({ key, ...data }) }).catch(e => { if (controller.signal.aborted) return; if (e instanceof ApiError && e.status === 403) navigate('/403', { replace: true }); else setState({ key, error: errorMessage(e) }) })
    return () => controller.abort()
  }, [date, roomTypeId, status, key, navigate])
  function update(change: Partial<typeof filters>) {
    const next = { ...filters, ...change }
    try { const query = dailyQuery(next); query.set('tab', 'daily'); setParams(query) }
    catch (e) { setState({ key, error: errorMessage(e) }) }
  }
  return <section className="daily-bookings"><h1>Booking theo ngày</h1><p>Khoảng ở giao với ngày đã chọn · Giờ Việt Nam · Chỉ đọc, liên hệ đã che.</p>
    <Card><div className="report-controls">
      <label className="staff-control">Ngày xem<input type="date" value={date} onChange={e => update({ date: e.target.value })} /></label>
      <label className="staff-control">Loại phòng<select value={roomTypeId} onChange={e => update({ roomTypeId: e.target.value })}><option value="">Tất cả</option>{roomTypeId && !types.some(t => String(t.id) === roomTypeId) && <option value={roomTypeId}>Loại #{roomTypeId}</option>}{types.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label>
      <label className="staff-control">Trạng thái<select value={status} onChange={e => update({ status: e.target.value })}><option value="">Tất cả trạng thái</option>{bookingStatuses.map(s => <option key={s} value={s}>{statusLabels[s]}</option>)}</select></label>
      {[-1, 0, 1].map((delta, i) => <Button key={delta} variant="secondary" onClick={() => update({ date: addHotelDays(hotelDate(), delta) })}>{['Hôm qua', 'Hôm nay', 'Ngày mai'][i]}</Button>)}
      <Button variant="secondary" onClick={() => setReload(n => n + 1)}>Tải lại</Button>
    </div>{typesError && <Alert tone="warning">Danh mục loại phòng: {typesError}</Alert>}</Card>
    {current.error && <Alert>{current.error}</Alert>}{!current.items && !current.error && <LoadingState message="Đang tải booking theo ngày…" />}
    {current.truncated && <Alert tone="warning">Chỉ hiển thị 500 kết quả đầu. Hãy lọc thêm loại phòng hoặc trạng thái.</Alert>}
    {current.items && <><p role="status">{current.items.length} booking</p><DataTable caption="Booking theo ngày" columns={['Mã', 'Trạng thái', 'Loại phòng', 'Phòng', 'Nhận – trả', 'Khách', 'Số tiền', 'SĐT đã che', 'Email đã che']} empty={<p>Không có booking phù hợp.</p>}>
      {current.items.length ? current.items.map(item => <tr key={item.bookingCode}><td><Link to={`/staff/calendar?${new URLSearchParams({ from: hotelDate(new Date(item.startAt)), roomTypeId: String(item.roomTypeId) })}`}>{item.bookingCode}</Link></td><td>{statusLabels[item.status]}</td><td>{item.roomTypeName}</td><td>{item.roomNumber}</td><td>{formatHotelTime(item.startAt)}<br />{formatHotelTime(item.endAt)}</td><td>{item.guestCount}</td><td>{formatVnd(item.payableAmount)}</td><td>{item.customer.phoneMasked}</td><td>{item.customer.emailMasked}</td></tr>) : undefined}
    </DataTable></>}
  </section>
}
