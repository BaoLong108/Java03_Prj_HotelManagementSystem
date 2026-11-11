import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, errorMessage } from '../../../shared/api/client'
import { Alert, Button, Card, Dialog, LoadingState } from '../../../shared/ui'
import { BlockHistory } from './BlockHistory'
import { getRealRoomCalendar } from '../calendar/roomCalendarApi'
import type { CalendarRoom } from '../calendar/roomCalendarTypes'
import { calendarRange, formatHotelTime, hotelDate } from '../shared/staffTime'
import { BlockForm, ReleaseBlockButton } from './BlockControls'
import { listBlocks } from './blockApi'
import type { RoomBlock } from './blockApi'
import '../shared/operations.css'

export function RoomOperations() {
  const [reload, setReload] = useState(0), [selected, setSelected] = useState<number | null>(null)
  const [state, setState] = useState<{ rooms?: CalendarRoom[]; blocks?: Record<number, RoomBlock[]>; failures?: number[]; error?: string }>({})
  const navigate = useNavigate()
  const [historyRoom, setHistoryRoom] = useState<number | null>(null)
  useEffect(() => {
    const controller = new AbortController(); setState({})
    getRealRoomCalendar(calendarRange(hotelDate()), controller.signal).then(async calendar => {
      const failures: number[] = []
      const entries = await Promise.all(calendar.rooms.map(async room => {
        try { return [room.physicalRoomId, await listBlocks(room.physicalRoomId, controller.signal)] as const }
        catch (e) { if (controller.signal.aborted || (e instanceof ApiError && [401, 403].includes(e.status))) throw e; failures.push(room.physicalRoomId); return [room.physicalRoomId, []] as const }
      }))
      if (!controller.signal.aborted) setState({ rooms: calendar.rooms, blocks: Object.fromEntries(entries), failures })
    }).catch(e => { if (controller.signal.aborted) return; if (e instanceof ApiError && e.status === 403) navigate('/403', { replace: true }); else setState({ error: errorMessage(e) }) })
    return () => controller.abort()
  }, [reload, navigate])
  const refresh = () => setReload(n => n + 1)
  return <section className="operations"><Button variant="secondary" onClick={refresh}>Tải lại phòng</Button>
    {!state.rooms && !state.error && <LoadingState />}{state.error && <Alert>{state.error}</Alert>}
    {state.rooms?.length === 0 && <p>Máy chủ không trả phòng vật lý.</p>}
    {state.rooms?.map(room => <Card key={room.physicalRoomId}>
      <div className="card-heading"><h2>Phòng {room.roomNumber} · {room.roomTypeName}</h2><span>{room.isActive ? 'Đang bán' : 'Ngừng bán'}</span></div>
      <Button disabled={!room.isActive} onClick={() => setSelected(room.physicalRoomId)}>Chặn phòng {room.roomNumber}</Button>
      <Button variant="ghost" onClick={() => setHistoryRoom(room.physicalRoomId)}>Lịch sử phòng {room.roomNumber}</Button>
      {state.failures?.includes(room.physicalRoomId) ? <Alert>Không tải được khoảng chặn. Hãy tải lại.</Alert> : <>
        {!state.blocks?.[room.physicalRoomId]?.length && <p className="muted">Không có khoảng chặn chưa được mở. Đây không phải xác nhận phòng trống.</p>}
        {state.blocks?.[room.physicalRoomId]?.map(block => <div className="block-row" key={block.id}><p><strong>{block.reason}</strong><br />{formatHotelTime(block.startAt)} → {formatHotelTime(block.endAt)}</p><ReleaseBlockButton id={block.id} onSuccess={refresh} /></div>)}
      </>}
    </Card>)}
    {selected !== null && state.rooms && <BlockForm rooms={state.rooms} initialRoom={selected} onClose={() => setSelected(null)} onSuccess={refresh} />}
    <Dialog open={historyRoom !== null} title="Lịch sử RoomBlock" onClose={() => setHistoryRoom(null)}>
      {historyRoom !== null && <BlockHistory key={historyRoom} roomId={historyRoom} />}
    </Dialog>
  </section>
}
