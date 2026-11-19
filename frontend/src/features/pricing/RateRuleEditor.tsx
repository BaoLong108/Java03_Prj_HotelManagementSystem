import { useState } from 'react'
import type { FormEvent } from 'react'
import { errorMessage } from '../../shared/api/client'
import { Alert, Button, ConfirmDialog, Field, Select } from '../../shared/ui'
import { formatVnd } from '../staff/bookings/bookingSearchFormat'
import { rateApi, rateLabels } from './rateApi'
import type { NightlyRate, RateDraft, RateKind, RatePreview, RateRule } from './rateApi'

type Props = { roomTypeId: number; day: NightlyRate; rangeEnd: string; rule: RateRule | null; onSaved: (message: string, date?: string) => void; onCancel: () => void }
const displayDate = (date: string) => date.split('-').reverse().join('/')

export function RateRuleEditor({ roomTypeId, day, rangeEnd, rule, onSaved, onCancel }: Props) {
  const [draft, setDraft] = useState<RateDraft>({ roomTypeId, kind: rule?.kind ?? 'OVERRIDE',
    startsOn: rule?.startsOn ?? day.date, endsOn: rule?.endsOn ?? (rangeEnd || day.date), nightlyRate: String(rule?.nightlyRate ?? day.nightlyRate) })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState<'save' | 'deactivate' | null>(null)
  const [preview, setPreview] = useState<RatePreview[] | null>(null)
  const originalScope = rule ? `Quy tắc hiện tại áp dụng từ ${displayDate(rule.startsOn)} đến ${displayDate(rule.endsOn)}.` : ''
  const multiple = draft.startsOn !== draft.endsOn || Boolean(rule && rule.startsOn !== rule.endsOn)

  function change<K extends keyof RateDraft>(field: K, value: RateDraft[K]) {
    setDraft(current => ({ ...current, [field]: value }))
    setPreview(null)
    setError('')
  }

  async function run(action: 'preview' | 'save' | 'deactivate') {
    if (busy) return
    setError('')
    setBusy(true)
    try {
      if (action === 'preview') setPreview(await rateApi.preview(draft, rule?.id))
      else if (action === 'save') {
        await rateApi.save(draft, rule?.id)
        onSaved('Đã lưu quy tắc giá.', draft.startsOn)
      } else if (rule) {
        await rateApi.deactivate(roomTypeId, rule.id)
        onSaved('Đã ngừng áp dụng quy tắc giá.')
      }
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (multiple) setConfirm('save')
    else void run('save')
  }

  return <form className="rate-form" onSubmit={submit}>
    <h3>{rule ? 'Sửa quy tắc giá' : 'Thêm quy tắc giá'}</h3>
    {rule && <p className="small muted">{originalScope} Lưu sẽ thay toàn bộ phạm vi của quy tắc.</p>}
    {error && <Alert>{error}</Alert>}
    <Select label="Loại quy tắc" value={draft.kind} disabled={busy} onChange={event => change('kind', event.target.value as RateKind)}>
      {Object.entries(rateLabels).map(([kind, label]) => <option key={kind} value={kind}>{label}</option>)}
    </Select>
    <div className="rate-range">
      <Field label="Từ đêm" type="date" min="1900-01-01" max="9999-12-31" required value={draft.startsOn} disabled={busy} onChange={event => change('startsOn', event.target.value)} />
      <Field label="Đến đêm (bao gồm)" type="date" min={draft.startsOn} max="9999-12-31" required value={draft.endsOn} disabled={busy} onChange={event => change('endsOn', event.target.value)} />
    </div>
    <Field label="Giá mỗi đêm (VND)" type="number" min="0" max="9999999999999.99" step="0.01" required value={draft.nightlyRate}
      disabled={busy} onChange={event => change('nightlyRate', event.target.value)} />
    {draft.kind === 'WEEKEND' && <p className="small muted">Chỉ áp dụng cho đêm thứ Bảy và Chủ nhật trong khoảng đã chọn.</p>}
    <div className="button-row">
      <Button variant="secondary" disabled={busy} onClick={event => { if (event.currentTarget.form?.reportValidity()) void run('preview') }}>Xem trước</Button>
      <Button type="submit" busy={busy}>Lưu quy tắc</Button>
      {rule && <Button variant="secondary" disabled={busy} onClick={() => setConfirm('deactivate')}>Ngừng áp dụng</Button>}
      {rule && <Button variant="ghost" disabled={busy} onClick={onCancel}>Hủy sửa</Button>}
    </div>
    {preview && <details open className="rate-preview">
      <summary>Xem trước {preview.filter(item => item.before.nightlyRate !== item.after.nightlyRate).length}/{preview.length} đêm đổi giá</summary>
      <ul>{preview.map(({ before, after }) => <li key={after.date}><span>{displayDate(after.date)}</span><span>{formatVnd(String(before.nightlyRate))} → <strong>{formatVnd(String(after.nightlyRate))}</strong></span></li>)}</ul>
    </details>}
    <ConfirmDialog open={confirm !== null} title={confirm === 'deactivate' ? 'Ngừng áp dụng quy tắc?' : 'Áp dụng giá cho khoảng ngày?'}
      description={confirm === 'deactivate' ? `${originalScope} Giá từng đêm sẽ trở về quy tắc ưu tiên tiếp theo.`
        : `${originalScope} Khoảng mới từ ${displayDate(draft.startsOn)} đến ${displayDate(draft.endsOn)}. Thay đổi có hiệu lực cho báo giá mới.`}
      onClose={() => setConfirm(null)} onConfirm={() => { if (confirm) void run(confirm) }} />
  </form>
}
