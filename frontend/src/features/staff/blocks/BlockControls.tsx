import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../../../shared/api/client'
import { Alert, Button, Dialog, Field, useToast } from '../../../shared/ui'
import { addHotelDays, hotelDate } from '../shared/staffTime'
import type { CalendarRoom } from '../calendar/roomCalendarTypes'
import { blockError, createBlock, releaseBlock, validateBlock } from './blockApi'
import type { BlockDraft } from './blockApi'

export function BlockForm({ rooms, date, initialRoom, onClose, onSuccess }: {
  rooms: CalendarRoom[]; date?: string; initialRoom?: number; onClose: () => void; onSuccess: () => void
}) {
  const day = date && date > hotelDate() ? date : addHotelDays(hotelDate(), 1)
  const [draft, setDraft] = useState<BlockDraft>({ physicalRoomId: String(initialRoom ?? rooms.find(r => r.isActive)?.physicalRoomId ?? ''), start: `${day}T12:00`, end: `${addHotelDays(day, 1)}T10:00`, reason: '' })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const lock = useRef(false), navigate = useNavigate(), notify = useToast()
  return <Dialog open title="Chặn phòng" onClose={() => { if (!lock.current) onClose() }}>
    <form noValidate onSubmit={async event => {
      event.preventDefault(); if (lock.current) return
      const validation = validateBlock(draft); setErrors(validation); setError('')
      if (Object.keys(validation).length) return
      lock.current = true; setBusy(true)
      try { await createBlock(draft); notify('Đã chặn phòng.'); onSuccess(); onClose() }
      catch (e) {
        if (e instanceof ApiError && e.status === 403) navigate('/403', { replace: true })
        else { setError(blockError(e)); if (e instanceof ApiError && e.status === 400) setErrors(e.details) }
      } finally { lock.current = false; setBusy(false) }
    }}>
      <p className="muted">Giờ Việt Nam (UTC+7). Máy chủ kiểm tra booking và các khoảng chặn trước khi lưu.</p>
      {error && <Alert>{error}</Alert>}
      <fieldset disabled={busy} className="block-fields">
        <label className="field">Phòng vật lý<select value={draft.physicalRoomId} onChange={e => setDraft({ ...draft, physicalRoomId: e.target.value })} aria-invalid={Boolean(errors.physicalRoomId)}>
          <option value="">Chọn phòng</option>{rooms.filter(r => r.isActive).map(r => <option key={r.physicalRoomId} value={r.physicalRoomId}>{r.roomNumber} · {r.roomTypeName}</option>)}
        </select>{errors.physicalRoomId && <span className="field-error">{errors.physicalRoomId}</span>}</label>
        <Field label="Bắt đầu (giờ Việt Nam)" type="datetime-local" value={draft.start} error={errors.startAt} onChange={e => setDraft({ ...draft, start: e.target.value })} />
        <Field label="Kết thúc (giờ Việt Nam)" type="datetime-local" value={draft.end} error={errors.endAt} onChange={e => setDraft({ ...draft, end: e.target.value })} />
        <Field label="Lý do" value={draft.reason} error={errors.reason} required onChange={e => setDraft({ ...draft, reason: e.target.value })} />
      </fieldset>
      <div className="dialog-actions"><Button variant="secondary" disabled={busy} onClick={onClose}>Quay lại</Button><Button type="submit" busy={busy}>Lưu khoảng chặn</Button></div>
    </form>
  </Dialog>
}

export function ReleaseBlockButton({ id, onSuccess }: { id: number; onSuccess: () => void }) {
  const [confirm, setConfirm] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const lock = useRef(false), navigate = useNavigate(), notify = useToast()
  return <>{error && <Alert>{error}</Alert>}
    <Button variant="secondary" disabled={busy} onClick={() => { setError(''); setConfirm(true) }}>Mở lại phòng</Button>
    <Dialog open={confirm} title="Mở lại phòng?" onClose={() => { if (!lock.current) setConfirm(false) }}>
      <p>Gỡ đúng khoảng chặn #{id}. Các booking và khoảng chặn khác được giữ nguyên.</p>
      {error && <Alert>{error}</Alert>}
      <div className="dialog-actions"><Button variant="secondary" disabled={busy} onClick={() => setConfirm(false)}>Quay lại</Button>
        <Button busy={busy} onClick={async () => {
          if (lock.current) return; lock.current = true; setBusy(true); setError('')
          try { await releaseBlock(id); setConfirm(false); notify('Đã mở lại phòng.'); onSuccess() }
          catch (e) { if (e instanceof ApiError && e.status === 403) navigate('/403', { replace: true }); else setError(blockError(e)) }
          finally { lock.current = false; setBusy(false) }
        }}>Xác nhận mở lại</Button></div>
    </Dialog>
  </>
}
