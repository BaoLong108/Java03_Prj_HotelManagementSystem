import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, errorMessage } from '../../../shared/api/client'
import { Alert, Button, Card, DataTable, LoadingState } from '../../../shared/ui'
import { getRealRoomCalendar } from '../calendar/roomCalendarApi'
import { calendarRange, formatHotelTime, hotelDate } from '../shared/staffTime'
import { getHistory, presetPeriod } from './reportApi'
import type { BlockHistory as Row } from './reportApi'
import { PeriodFields } from './ReportControls'
import './reports.css'

export function BlockHistory() {
  const [period, setPeriod] = useState(() => presetPeriod('30')), [roomId, setRoomId] = useState(''), [reload, setReload] = useState(0)
  const [rooms, setRooms] = useState<{ physicalRoomId: number; roomNumber: string }[]>([]), [roomsError, setRoomsError] = useState('')
  const navigate = useNavigate(), key = `${period.from}|${period.to}|${roomId}|${reload}`
  const [state, setState] = useState<{ key: string; items?: Row[]; error?: string }>({ key: '' })
  const current = state.key === key ? state : { key }
  useEffect(() => {
    const c = new AbortController()
    getRealRoomCalendar(calendarRange(hotelDate()), c.signal).then(data => { if (!c.signal.aborted) { setRooms(data.rooms); setRoomsError('') } }).catch(e => { if (!c.signal.aborted) setRoomsError(errorMessage(e)) })
    return () => c.abort()
  }, [reload])
  useEffect(() => {
    const c = new AbortController()
    Promise.resolve().then(() => getHistory({ from: period.from, to: period.to }, roomId, c.signal)).then(items => { if (!c.signal.aborted) setState({ key, items }) }).catch(e => {
      if (c.signal.aborted) return
      if (e instanceof ApiError && e.status === 403) navigate('/403', { replace: true }); else setState({ key, error: errorMessage(e) })
    })
    return () => c.abort()
  }, [period.from, period.to, roomId, key, navigate])
  return <section className="reports block-history"><h2>Lịch sử chặn phòng</h2><p>Khoảng chặn giao với kỳ đã chọn; gồm cả bản ghi đã mở. Thông tin chỉ đọc, giờ Việt Nam.</p>
    <Card><div className="report-controls"><PeriodFields period={period} onChange={setPeriod} />
      <label className="staff-control">Lọc phòng<select value={roomId} onChange={e => setRoomId(e.target.value)}><option value="">Tất cả phòng</option>{rooms.map(r => <option key={r.physicalRoomId} value={r.physicalRoomId}>{r.roomNumber}</option>)}</select></label>
      <Button variant="secondary" onClick={() => setReload(n => n + 1)}>Tải lại lịch sử</Button></div>{roomsError && <Alert>Danh sách phòng: {roomsError}</Alert>}</Card>
    {current.error && <Alert>{current.error}</Alert>}{!current.error && !current.items && <LoadingState message="Đang tải lịch sử…" />}
    {current.items && <DataTable caption="Lịch sử chặn phòng" columns={['Phòng', 'Từ', 'Đến', 'Lý do', 'Người chặn', 'Lúc chặn', 'Người mở', 'Lúc mở', 'Trạng thái']} empty={<p>Không có khoảng chặn phù hợp.</p>}>
      {current.items.length ? current.items.map(row => <tr key={row.id}><td>{row.roomNumber}</td><td>{formatHotelTime(row.startAt)}</td><td>{formatHotelTime(row.endAt)}</td><td>{row.reason}</td><td>{row.createdByName}</td><td>{formatHotelTime(row.createdAt)}</td><td>{row.releasedByName ?? '—'}</td><td>{row.releasedAt ? formatHotelTime(row.releasedAt) : '—'}</td><td>{row.active ? 'Đang hiệu lực' : 'Đã mở'}</td></tr>) : undefined}
    </DataTable>}
  </section>
}
