import { useEffect, useState } from 'react'
import { errorMessage } from '../../../shared/api/client'
import { Alert, Button, Card, Dialog, EmptyState, Field, LinkButton, PageHeader, Select, Skeleton } from '../../../shared/ui'
import { useAuth } from '../../auth/AuthProvider'
import { bookingStatusLabels } from '../../booking/types'
import { formatHotelTime, hotelDate } from '../shared/staffTime'
import { floorApi } from './floorApi'
import type { FloorOverview, FloorStatus } from './floorApi'
import './floorMap.css'

const labels: Record<FloorStatus, string> = {
  AVAILABLE: 'Trống theo phân phòng',
  ASSIGNED: 'Đã gán booking',
  HELD: 'Đang giữ chỗ',
  BLOCKED: 'Đang bị chặn',
  OUT_OF_SERVICE: 'Ngừng bán',
}
const states = Object.keys(labels) as FloorStatus[]
const floorLabel = (floor: number | null) => floor === null ? 'Chưa xác định tầng' : `Tầng ${floor}`

export function FloorMapPage() {
  const { user } = useAuth()
  const [data, setData] = useState<FloorOverview | null>(null)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  const [loading, setLoading] = useState(true)
  const [floorFilter, setFloorFilter] = useState('')
  const [typeFilter, setTypeFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [search, setSearch] = useState('')
  const [roomId, setRoomId] = useState<number | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    floorApi.overview(controller.signal).then(result => {
      if (!controller.signal.aborted) setData(result)
    }).catch(reason => {
      if (!controller.signal.aborted) { setData(null); setError(errorMessage(reason)) }
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false)
    })
    return () => controller.abort()
  }, [refresh, user?.id])

  const rooms = data?.floors.flatMap(floor => floor.rooms) ?? []
  const types = new Map(rooms.map(room => [room.roomTypeId, room.roomTypeName]))
  const query = search.trim().toLocaleLowerCase('vi-VN')
  const visible = (data?.floors ?? []).filter(floor => !floorFilter || String(floor.floorNumber) === floorFilter)
    .map(floor => ({ ...floor, rooms: floor.rooms.filter(room =>
      (!typeFilter || String(room.roomTypeId) === typeFilter) && (!statusFilter || room.operationalStatus === statusFilter)
      && room.roomNumber.toLocaleLowerCase('vi-VN').includes(query)) }))
    .filter(floor => floor.rooms.length > 0)
  const selectedFloor = data?.floors.find(floor => floor.rooms.some(room => room.roomId === roomId))
  const selected = selectedFloor?.rooms.find(room => room.roomId === roomId)

  return <div className="floor-map">
    <PageHeader eyebrow="VẬN HÀNH PHÒNG" title="Sơ đồ tầng" description="Xem phòng vật lý theo tầng và tình trạng phân phòng tại thời điểm tải dữ liệu."
      action={<Button variant="secondary" busy={loading} onClick={() => setRefresh(value => value + 1)}>Làm mới</Button>} />
    <p className="muted floor-time" aria-live="polite">{data && `Cập nhật lúc ${formatHotelTime(data.asOf)} · Giờ Việt Nam. `}Phân phòng theo booking chưa xác nhận khách đã nhận phòng.</p>
    {error && <Alert>{error}</Alert>}
    {loading && !data && <Card><Skeleton lines={5} /></Card>}
    {data && <>
      <ul className="floor-metrics" aria-label="Tổng số phòng theo trạng thái">
        {states.map(status => <li key={status} data-state={status}><strong>{rooms.filter(room => room.operationalStatus === status).length}</strong><span>{labels[status]}</span></li>)}
      </ul>
      <Card className="floor-filters">
        <Field label="Tìm số phòng" value={search} onChange={event => setSearch(event.target.value)} placeholder="VD: 201" />
        <Select label="Tầng" value={floorFilter} onChange={event => setFloorFilter(event.target.value)}>
          <option value="">Tất cả tầng</option>{data.floors.map(floor => <option key={String(floor.floorNumber)} value={String(floor.floorNumber)}>{floorLabel(floor.floorNumber)}</option>)}
        </Select>
        <Select label="Loại phòng" value={typeFilter} onChange={event => setTypeFilter(event.target.value)}>
          <option value="">Tất cả loại phòng</option>{Array.from(types, ([id, name]) => <option key={id} value={id}>{name}</option>)}
        </Select>
        <Select label="Trạng thái" value={statusFilter} onChange={event => setStatusFilter(event.target.value)}>
          <option value="">Tất cả trạng thái</option>{states.map(status => <option key={status} value={status}>{labels[status]}</option>)}
        </Select>
      </Card>
      {visible.length === 0 && <Card><EmptyState title={rooms.length ? 'Không có phòng phù hợp' : 'Chưa có phòng vật lý'}
        description={rooms.length ? 'Hãy đổi từ khóa hoặc bộ lọc.' : 'Danh mục phòng sẽ xuất hiện khi khách sạn có phòng vật lý.'} /></Card>}
      {visible.map(floor => <section className="floor-section" key={String(floor.floorNumber)}>
        <div className="floor-heading"><h2>{floorLabel(floor.floorNumber)}</h2><span>{floor.rooms.length} phòng</span></div>
        <div className="floor-rooms">{floor.rooms.map(room => <button type="button" className="floor-room" key={room.roomId}
          data-state={room.operationalStatus} aria-haspopup="dialog" onClick={() => setRoomId(room.roomId)}>
          <strong>{room.roomNumber}</strong><span className="floor-type">{room.roomTypeName}</span><span className="floor-state">{labels[room.operationalStatus]}</span>
          {room.block && room.operationalStatus !== 'BLOCKED' && <small>Có lịch chặn</small>}
          {room.assignment && <small>{room.assignment.bookingCode}</small>}
        </button>)}</div>
      </section>)}
    </>}
    <Dialog open={Boolean(selected)} className="floor-drawer" title={selected ? `Phòng ${selected.roomNumber}` : 'Chi tiết phòng'} onClose={() => setRoomId(null)}>
      {selected && selectedFloor && <div className="floor-detail">
        <dl><dt>Vị trí</dt><dd>{floorLabel(selectedFloor.floorNumber)}</dd><dt>Loại phòng</dt><dd>{selected.roomTypeName}</dd>
          <dt>Trạng thái vận hành</dt><dd>{labels[selected.operationalStatus]}</dd><dt>Vệ sinh phòng</dt><dd>Chưa có dữ liệu</dd>
          <dt>Cập nhật thông tin phòng</dt><dd>{formatHotelTime(selected.updatedAt)}</dd></dl>
        <section><h3>Lịch chặn hiện tại</h3>{selected.block ? <><p>{selected.block.reason}</p><p className="small muted">{formatHotelTime(selected.block.startAt)} → {formatHotelTime(selected.block.endAt)}</p></> : <p className="muted">Không có lịch chặn đang hiệu lực.</p>}</section>
        <section><h3>Phân phòng hiện tại</h3>{selected.assignment ? <><p>{selected.assignment.bookingCode} · {bookingStatusLabels[selected.assignment.status]}</p><p className="small muted">{formatHotelTime(selected.assignment.startAt)} → {formatHotelTime(selected.assignment.endAt)}</p></> : <p className="muted">Không có booking đang được gán trong khoảng này.</p>}</section>
        <div className="button-row">
          <LinkButton to={`/staff/calendar?from=${hotelDate(new Date(data?.asOf ?? Date.now()))}&roomTypeId=${selected.roomTypeId}`} variant="secondary">Xem lịch phòng</LinkButton>
          <LinkButton to="/staff/room-blocks" variant="secondary">Chặn và mở phòng</LinkButton>
          {selected.assignment && <LinkButton to="/staff/bookings" variant="secondary">Tra cứu booking</LinkButton>}
          {user?.roles.includes('ADMIN') && <LinkButton to={`/admin/rooms?roomTypeId=${selected.roomTypeId}`} variant="secondary">Danh mục phòng</LinkButton>}
        </div>
      </div>}
    </Dialog>
  </div>
}
