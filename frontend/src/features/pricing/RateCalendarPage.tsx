import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { errorMessage } from '../../shared/api/client'
import { Alert, Button, Card, ConfirmDialog, EmptyState, Field, PageHeader, Select, Skeleton } from '../../shared/ui'
import { adminRoomApi } from '../rooms/adminApi'
import type { RoomTypeResponse } from '../rooms/api'
import { formatVnd } from '../staff/bookings/bookingSearchFormat'
import { hotelDate } from '../staff/shared/staffTime'
import { RateRuleEditor } from './RateRuleEditor'
import { rateApi, rateLabels } from './rateApi'
import type { RateCalendar, RateRule } from './rateApi'
import './rateCalendar.css'

const weekdays = ['Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7', 'CN']
const monthPattern = /^\d{4}-(0[1-9]|1[0-2])$/
const dateLabel = (date: string) => date.split('-').reverse().join('/')
const weekday = (date: string) => (new Date(`${date}T12:00:00Z`).getUTCDay() + 6) % 7
const rateText = (rate: number) => formatVnd(String(rate))

function BaseRateEditor({ calendar, onSaved }: { calendar: RateCalendar; onSaved: (message: string) => void }) {
  const [price, setPrice] = useState(String(calendar.baseRate))
  const [confirm, setConfirm] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function save() {
    setBusy(true)
    setError('')
    try {
      await rateApi.base(calendar.roomTypeId, price)
      onSaved('Đã cập nhật giá cơ bản.')
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); setConfirm(true) }
  return <form className="rate-base-form" onSubmit={submit}>
    {error && <Alert>{error}</Alert>}
    <Field label="Giá cơ bản (VND)" type="number" min="0" max="9999999999999.99" step="0.01" required disabled={busy}
      value={price} onChange={event => setPrice(event.target.value)} />
    <Button type="submit" variant="secondary" busy={busy} disabled={Number(price) === calendar.baseRate}>Lưu giá cơ bản</Button>
    <ConfirmDialog open={confirm} title="Đổi giá cơ bản của loại phòng?"
      description="Giá cơ bản mới áp dụng cho mọi đêm không có quy tắc giá ưu tiên. Booking đã chốt vẫn dùng giá đã lưu."
      onClose={() => setConfirm(false)} onConfirm={() => void save()} />
  </form>
}

export function RateCalendarPage() {
  const [params] = useSearchParams()
  const requestedId = params.get('roomTypeId')
  const [types, setTypes] = useState<RoomTypeResponse[] | null>(null)
  const [typeId, setTypeId] = useState('')
  const [month, setMonth] = useState(() => hotelDate().slice(0, 7))
  const [date, setDate] = useState('')
  const [rangeAnchor, setRangeAnchor] = useState('')
  const [rangeEnd, setRangeEnd] = useState('')
  const [calendar, setCalendar] = useState<RateCalendar | null>(null)
  const [edit, setEdit] = useState<RateRule | null>(null)
  const [typeError, setTypeError] = useState('')
  const [calendarError, setCalendarError] = useState('')
  const [notice, setNotice] = useState('')
  const [reloadTypes, setReloadTypes] = useState(0)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    setTypeError('')
    adminRoomApi.types(controller.signal).then(result => {
      if (controller.signal.aborted) return
      const active = result.filter(type => type.active)
      setTypes(active)
      const selected = active.find(type => String(type.id) === requestedId) ?? active[0]
      setTypeId(selected ? String(selected.id) : '')
    }).catch(reason => { if (!controller.signal.aborted) setTypeError(errorMessage(reason)) })
    return () => controller.abort()
  }, [requestedId, reloadTypes])

  useEffect(() => {
    if (!typeId) return
    const controller = new AbortController()
    setCalendar(null)
    setCalendarError('')
    rateApi.month(Number(typeId), month, controller.signal).then(result => {
      if (!controller.signal.aborted) setCalendar(result)
    }).catch(reason => { if (!controller.signal.aborted) setCalendarError(errorMessage(reason)) })
    return () => controller.abort()
  }, [month, reload, typeId])

  const active = calendar?.roomTypeId === Number(typeId) && calendar.month === month ? calendar : null
  const day = active?.nights.find(night => night.date === date) ?? active?.nights[0]
  const rules = day && active ? active.rules.filter(rule => rule.startsOn <= day.date && rule.endsOn >= day.date
    && (rule.kind !== 'WEEKEND' || day.weekend)) : []

  function chooseMonth(value: string) {
    if (!monthPattern.test(value) || Number(value.slice(0, 4)) < 1900) return
    setEdit(null)
    setDate('')
    setRangeAnchor('')
    setRangeEnd('')
    setMonth(value)
    setNotice('')
  }

  function moveMonth(delta: number) {
    const [year, number] = month.split('-').map(Number)
    chooseMonth(new Date(Date.UTC(year, number - 1 + delta, 1)).toISOString().slice(0, 7))
  }

  function saved(message: string, savedDate?: string) {
    if (savedDate) {
      setMonth(savedDate.slice(0, 7))
      setDate(savedDate)
    }
    setRangeAnchor('')
    setRangeEnd('')
    setNotice(message)
    setEdit(null)
    setCalendar(null)
    setReload(value => value + 1)
  }

  function chooseDay(value: string) {
    const [from, to] = rangeAnchor ? [rangeAnchor, value].sort() : [value, '']
    setDate(from)
    setRangeEnd(to)
    setRangeAnchor('')
    setEdit(null)
  }

  return <div className="rate-page">
    <PageHeader eyebrow="QUẢN TRỊ GIÁ PHÒNG" title="Lịch giá phòng" description="Xem và cấu hình giá từng đêm theo loại phòng, trước ưu đãi thành viên và voucher." />
    {notice && <Alert tone="success">{notice}</Alert>}
    {typeError && <Card><Alert>{typeError}</Alert><Button variant="secondary" onClick={() => setReloadTypes(value => value + 1)}>Tải lại loại phòng</Button></Card>}
    {!types && !typeError && <Card><Skeleton lines={4} /></Card>}
    {types?.length === 0 && <Card><EmptyState title="Chưa có loại phòng đang mở bán" description="Thêm hoặc mở bán một loại phòng trong danh mục phòng để cấu hình giá." /></Card>}
    {Boolean(types?.length) && <Card className="rate-toolbar">
      <Select label="Loại phòng" value={typeId} onChange={event => { setTypeId(event.target.value); setEdit(null); setRangeAnchor(''); setRangeEnd(''); setNotice('') }}>
        {types?.map(type => <option key={type.id} value={type.id}>{type.name} ({type.code})</option>)}
      </Select>
      <div className="rate-month-nav">
        <Button variant="secondary" aria-label="Tháng trước" disabled={month === '1900-01'} onClick={() => moveMonth(-1)}>‹</Button>
        <Field label="Tháng xem giá" type="month" min="1900-01" max="9999-12" value={month} onChange={event => chooseMonth(event.target.value)} />
        <Button variant="secondary" aria-label="Tháng sau" disabled={month === '9999-12'} onClick={() => moveMonth(1)}>›</Button>
      </div>
    </Card>}
    {calendarError && <Card><Alert>{calendarError}</Alert><Button variant="secondary" onClick={() => setReload(value => value + 1)}>Tải lại lịch giá</Button></Card>}
    {typeId && !active && !calendarError && <Card><Skeleton lines={6} /></Card>}
    {active && day && <>
      <Card className="rate-base"><BaseRateEditor key={`${typeId}-${active.baseRate}`} calendar={active} onSaved={saved} />
        <p className="small muted">Ưu tiên giá chỉ định → ngày lễ → theo mùa → cuối tuần → giá cơ bản.</p></Card>
      <div className="rate-workspace">
        <Card className="rate-grid-card">
          <div className="rate-grid-heading"><h2>Tháng {month.slice(5)}/{month.slice(0, 4)}</h2>
            <Button variant="secondary" aria-pressed={Boolean(rangeAnchor)} onClick={() => setRangeAnchor(rangeAnchor ? '' : day.date)}>
              {rangeAnchor ? 'Hủy chọn khoảng' : 'Chọn khoảng đêm'}
            </Button></div>
          {rangeAnchor && <p className="small muted" role="status">Đã chọn {dateLabel(rangeAnchor)}. Chọn đêm còn lại trên lịch.</p>}
          <div className="rate-grid" aria-label="Giá từng đêm trong tháng">
            {weekdays.map(label => <span className="rate-weekday" key={label}>{label}</span>)}
            {Array.from({ length: weekday(active.nights[0].date) }, (_, index) => <span className="rate-padding" aria-hidden="true" key={index} />)}
            {active.nights.map(night => <button type="button" key={night.date} className="rate-cell" data-weekend={night.weekend}
              data-kind={night.kind ?? 'BASE'} aria-pressed={night.date === day.date || Boolean(rangeEnd && day.date <= night.date && night.date <= rangeEnd)}
              aria-label={`${dateLabel(night.date)} · ${rateText(night.nightlyRate)} · ${night.kind ? rateLabels[night.kind] : 'Giá cơ bản'}`}
              onClick={() => chooseDay(night.date)}>
              <span className="rate-day"><span className="rate-mobile-weekday">{weekdays[weekday(night.date)]} · </span>{Number(night.date.slice(8))}</span>
              <strong>{rateText(night.nightlyRate)}</strong>
              {night.kind && <small>{rateLabels[night.kind]}</small>}
            </button>)}
          </div>
        </Card>
        <Card className="rate-day-detail">
          <h2>Đêm {dateLabel(day.date)}</h2>
          <p><strong>{rateText(day.nightlyRate)}</strong> · {day.kind ? rateLabels[day.kind] : 'Giá cơ bản'}</p>
          {rules.length > 0 && <ul className="rate-rule-list" aria-label="Quy tắc áp dụng trong ngày">
            {rules.map(rule => <li key={rule.id}><span>{rateLabels[rule.kind]} · {dateLabel(rule.startsOn)}–{dateLabel(rule.endsOn)}</span>
              <Button variant="secondary" aria-label={`Sửa quy tắc ${rateLabels[rule.kind]}`} onClick={() => setEdit(rule)}>Sửa</Button></li>)}
          </ul>}
          <RateRuleEditor key={`${typeId}:${day.date}:${rangeEnd}:${edit?.id ?? 'new'}`} roomTypeId={active.roomTypeId} day={day} rangeEnd={rangeEnd}
            rule={edit} onSaved={saved} onCancel={() => setEdit(null)} />
        </Card>
      </div>
    </>}
  </div>
}
