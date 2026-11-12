import { useState } from 'react'
import type { FormEvent } from 'react'
import { Alert, Button, Card, Field, PageHeader } from '../../../shared/ui'
import { apiRequest, errorMessage } from '../../../shared/api/client'
import { hotelToday, addDays } from '../../rooms/search'

export function BookingExportPage() {
  const [from, setFrom] = useState(hotelToday)
  const [to, setTo] = useState(() => addDays(hotelToday(), 6))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function download(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true); setError('')
    try {
      const query = new URLSearchParams({ from, to })
      const blob = await apiRequest<Blob>(`/api/v1/admin/bookings/export.csv?${query}`, { responseType: 'blob' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url; link.download = `booking-calendar-${from}-${to}.csv`; link.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (error) { setError(errorMessage(error)) } finally { setBusy(false) }
  }
  return <><PageHeader title="Xuất lịch đặt phòng" description="Tải booking và các khoảng chặn giao với kỳ đã chọn, theo giờ Việt Nam." />
    <Card><form className="report-controls" onSubmit={download}>
      <Field label="Từ ngày" type="date" value={from} onChange={e => setFrom(e.target.value)} required />
      <Field label="Đến ngày" type="date" value={to} min={from} max={addDays(from, 365)} onChange={e => setTo(e.target.value)} required />
      <Button type="submit" busy={busy}>Tải CSV lịch đặt phòng</Button>
    </form><p className="small muted">Tối đa 366 ngày và 10.000 dòng. Dòng BOOKING dùng giá đã chốt, dòng BLOCK gồm lý do và trạng thái mở.</p>{error && <Alert>{error}</Alert>}</Card></>
}
