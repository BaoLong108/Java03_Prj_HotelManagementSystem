import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Button, Dialog, Field } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { addDays, hotelToday, readStay, stayParams, validateStay } from './search'

export function StaySearchForm({ compact = false }: { compact?: boolean }) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [stay, setStay] = useState(() => readStay(params))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [guestsOpen, setGuestsOpen] = useState(false)
  const query = params.toString()
  useEffect(() => { setStay(readStay(new URLSearchParams(query))); setErrors({}) }, [query])
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    // Read displayed values as well, including native date-picker/autofill changes.
    const data = new FormData(event.currentTarget)
    const submitted = { ...stay, arrival: String(data.get('arrival') ?? ''), departure: String(data.get('departure') ?? '') }
    const nextErrors = validateStay(submitted)
    setStay(submitted)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length) return
    navigate(`/rooms?${stayParams(submitted)}`)
  }
  return <><form onSubmit={submit} className={`stay-search ${compact ? 'stay-search--compact' : ''}`} aria-label="Lên kế hoạch kỳ nghỉ" noValidate>
    <div className="search-intro"><Icon name="building" size={23} /><div><strong>Hotel PMS Mini</strong><small>Không gian dành cho bạn</small></div></div>
    <Field label="Ngày đến" name="arrival" type="date" value={stay.arrival} min={hotelToday()} required error={errors.arrival} onChange={(event) => {
      const arrival = event.target.value
      setStay({ ...stay, arrival, departure: !stay.departure || stay.departure <= arrival ? addDays(arrival, 1) : stay.departure })
    }} />
    <Field label="Ngày đi" name="departure" type="date" value={stay.departure} min={addDays(stay.arrival, 1) || hotelToday()} required error={errors.departure} onChange={(event) => setStay({ ...stay, departure: event.target.value })} />
    <div className="field guest-picker-field"><span className="field-label" id="guests-label">Số khách</span><button type="button" className="guest-picker-trigger" aria-labelledby="guests-label guests-value" aria-haspopup="dialog" onClick={() => setGuestsOpen(true)}><span id="guests-value">{stay.adults + stay.children} khách</span><Icon name="down" size={15} /></button>{errors.guests && <p className="field-error">{errors.guests}</p>}</div>
    <Button type="submit"><Icon name="search" size={18} />Tìm phòng</Button>
  </form><Dialog open={guestsOpen} title="Ai sẽ cùng bạn nghỉ ngơi?" onClose={() => setGuestsOpen(false)}>
    {(['adults', 'children'] as const).map((key) => <div className="guest-counter" key={key}><div><strong>{key === 'adults' ? 'Người lớn' : 'Trẻ em'}</strong><p className="small muted">{key === 'adults' ? 'Người lớn trong đoàn' : 'Trẻ em đi cùng'}</p></div><div className="counter-controls">
      <button className="icon-button" disabled={stay[key] <= (key === 'adults' ? 1 : 0)} aria-label={`Giảm ${key === 'adults' ? 'người lớn' : 'trẻ em'}`} onClick={() => setStay({ ...stay, [key]: stay[key] - 1 })}><Icon name="minus" size={16} /></button>
      <output aria-live="polite">{stay[key]}</output><button className="icon-button" disabled={stay[key] >= 10} aria-label={`Tăng ${key === 'adults' ? 'người lớn' : 'trẻ em'}`} onClick={() => setStay({ ...stay, [key]: stay[key] + 1 })}><Icon name="plus" size={16} /></button></div></div>)}
    <p className="small muted">Sức chứa và chính sách trẻ em sẽ được xác nhận theo từng loại phòng.</p><div className="dialog-actions"><Button onClick={() => setGuestsOpen(false)}>Áp dụng <Icon name="check" size={16} /></Button></div></Dialog></>
}
