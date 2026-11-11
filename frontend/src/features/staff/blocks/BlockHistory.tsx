import { useEffect, useState } from 'react'
import { Alert, Button, DataTable, LoadingState } from '../../../shared/ui'
import { apiRequest, errorMessage } from '../../../shared/api/client'
import { formatHotelTime } from '../shared/staffTime'
import type { RoomBlock } from './blockApi'

export function BlockHistory({ roomId }: { roomId: number }) {
  const [rows, setRows] = useState<RoomBlock[] | null>(null)
  const [error, setError] = useState('')
  const [reload, setReload] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    apiRequest<RoomBlock[]>(`/api/v1/admin/room-blocks?roomId=${roomId}&history=true`, { signal: controller.signal })
      .then(data => { if (!controller.signal.aborted) setRows(data) })
      .catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)) })
    return () => controller.abort()
  }, [roomId, reload])
  return <div><h3>Lịch sử chặn và mở phòng</h3>{error ? <Alert>{error} <Button variant="ghost" onClick={() => { setError(''); setReload(n => n + 1) }}>Thử lại</Button></Alert> : rows === null ? <LoadingState /> :
    <DataTable caption="Lịch sử RoomBlock" columns={['Khoảng chặn', 'Lý do', 'Người chặn', 'Tạo lúc', 'Người mở', 'Mở lúc']}
      empty={<p>Chưa có lịch sử.</p>}>{rows.length ? rows.map(row => <tr key={row.id}>
        <td>{formatHotelTime(row.startAt)} – {formatHotelTime(row.endAt)}</td><td>{row.reason}</td>
        <td>Nhân viên #{row.createdByUserId}</td><td>{formatHotelTime(row.createdAt)}</td>
        <td>{row.releasedByUserId ? `Nhân viên #${row.releasedByUserId}` : '—'}</td><td>{row.releasedAt ? formatHotelTime(row.releasedAt) : 'Chưa mở'}</td>
      </tr>) : undefined}</DataTable>}</div>
}
