import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Alert, Badge, Button, Dialog, Field, LoadingState } from '../../shared/ui'
import { errorMessage } from '../../shared/api/client'
import { adminRoomApi } from './adminApi'
import type { PhysicalRoom } from './adminApi'
import { listBlocks } from '../staff/blocks/blockApi'
import type { RoomBlock } from '../staff/blocks/blockApi'
import { formatHotelTime } from '../staff/shared/staffTime'

export function RoomInventoryItem({ room, onChange }: { room: PhysicalRoom; onChange: () => void }) {
  const [action, setAction] = useState<'edit' | 'hide' | 'blocks' | null>(null)
  const [number, setNumber] = useState(room.roomNumber)
  const [floor, setFloor] = useState(String(room.floorNumber ?? ''))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true); setError('')
    try {
      if (action === 'hide') await adminRoomApi.hide(room.id)
      else await adminRoomApi.update(room.id, number.trim(), floor ? Number(floor) : null)
      setAction(null); onChange()
    } catch (error) { setError(errorMessage(error)) } finally { setBusy(false) }
  }
  function open(next: typeof action) { setNumber(room.roomNumber); setFloor(String(room.floorNumber ?? '')); setError(''); setAction(next) }
  return <div className="room-inventory-item"><strong>{room.roomNumber}</strong><span>{room.floorNumber ? `Tầng ${room.floorNumber}` : 'Chưa ghi tầng'}</span>
    <Badge tone={room.active ? 'success' : 'neutral'}>{room.active ? 'Hoạt động' : 'Đã ẩn'}</Badge>
    <div className="button-row"><Button variant="ghost" onClick={() => open('edit')}>Sửa phòng {room.roomNumber}</Button>
      {room.active && <Button variant="ghost" onClick={() => open('hide')}>Ẩn phòng {room.roomNumber}</Button>}
      <Button variant="secondary" onClick={() => open('blocks')}>Lịch chặn</Button></div>
    <Dialog open={action !== null} title={action === 'blocks' ? `Lịch chặn phòng ${room.roomNumber}` : action === 'hide' ? 'Ẩn phòng khỏi danh mục bán?' : 'Sửa thông tin phòng'}
      onClose={() => { if (!busy) setAction(null) }}>
      {action === 'blocks' ? <UpcomingBlocks key={room.id} roomId={room.id} /> : <form onSubmit={save}>
        {error && <Alert>{error}</Alert>}
        {action === 'hide' ? <p>Phòng {room.roomNumber} sẽ ngừng nhận đặt phòng mới. Máy chủ kiểm tra các booking đang giữ chỗ hoặc đã xác nhận trước khi xử lý.</p> : <>
          <Field label="Số phòng" value={number} required maxLength={30} disabled={busy} onChange={e => setNumber(e.target.value)} />
          <Field label="Tầng" type="number" min={1} max={32767} value={floor} disabled={busy} onChange={e => setFloor(e.target.value)} />
        </>}
        <div className="dialog-actions"><Button variant="secondary" disabled={busy} onClick={() => setAction(null)}>Quay lại</Button>
          <Button type="submit" busy={busy}>{action === 'hide' ? 'Xác nhận ẩn phòng' : 'Lưu thông tin phòng'}</Button></div>
      </form>}
    </Dialog>
  </div>
}

function UpcomingBlocks({ roomId }: { roomId: number }) {
  const [rows, setRows] = useState<RoomBlock[] | null>(null)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    listBlocks(roomId, controller.signal).then(data => { if (!controller.signal.aborted) setRows(data.filter(row => Date.parse(row.endAt) > Date.now())) })
      .catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)) })
    return () => controller.abort()
  }, [roomId, retry])
  return error ? <Alert>{error} <Button variant="ghost" onClick={() => { setError(''); setRetry(n => n + 1) }}>Thử lại</Button></Alert> : rows === null ? <LoadingState /> :
    rows.length === 0 ? <p>Chưa có lịch chặn hiện tại hoặc sắp tới.</p> :
      <ul>{rows.map(row => <li key={row.id}><strong>{row.reason}</strong><p>{formatHotelTime(row.startAt)} – {formatHotelTime(row.endAt)}</p></li>)}</ul>
}
