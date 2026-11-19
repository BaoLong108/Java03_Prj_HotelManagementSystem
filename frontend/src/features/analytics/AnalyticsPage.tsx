import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { errorMessage } from '../../shared/api/client'
import { Alert, Button, Card, Field, PageHeader, Select, Skeleton } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { formatVnd } from '../booking/types'
import { addDays, displayDate, hotelToday, validDate } from '../rooms/search'
import { analyticsApi } from './analyticsApi'
import type { AnalyticsFilter, AnalyticsReport, AnalyticsRoomType } from './analyticsApi'
import { AnalyticsCharts } from './AnalyticsCharts'
import './analytics.css'

const presets = [
  ['today', 'Hôm nay'], ['week', '7 ngày qua'], ['monthDays', '30 ngày qua'],
  ['month', 'Tháng này'], ['previous', 'Tháng trước'], ['custom', 'Tùy chọn'],
] as const
const count = new Intl.NumberFormat('vi-VN')
const percent = (value: number) => new Intl.NumberFormat('vi-VN', { style: 'percent', maximumFractionDigits: 2 }).format(value / 100)

function dates(preset: string): AnalyticsFilter {
  const today = hotelToday()
  const monthStart = `${today.slice(0, 7)}-01`
  if (preset === 'previous') {
    const previous = addDays(monthStart, -1)
    return { from: `${previous.slice(0, 7)}-01`, to: previous }
  }
  const from = { today, week: addDays(today, -6), monthDays: addDays(today, -29), month: monthStart }[preset] ?? today
  return { from, to: today }
}

export function AnalyticsPage() {
  const [preset, setPreset] = useState('monthDays')
  const [filter, setFilter] = useState(() => dates('monthDays'))
  const [draft, setDraft] = useState(filter)
  const [types, setTypes] = useState<AnalyticsRoomType[]>([])
  const [result, setResult] = useState<AnalyticsReport | null>(null)
  const [error, setError] = useState('')
  const [typeError, setTypeError] = useState('')
  const [filterError, setFilterError] = useState('')
  const [exportError, setExportError] = useState('')
  const [exporting, setExporting] = useState(false)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    analyticsApi.roomTypes(controller.signal).then(data => {
      if (!controller.signal.aborted) { setTypes(data); setTypeError('') }
    }).catch(error => { if (!controller.signal.aborted) setTypeError(errorMessage(error)) })
    return () => controller.abort()
  }, [reload])

  useEffect(() => {
    const controller = new AbortController()
    analyticsApi.report(filter, controller.signal).then(data => { if (!controller.signal.aborted) setResult(data) })
      .catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)) })
    return () => controller.abort()
  }, [filter, reload])

  function apply(next: AnalyticsFilter) {
    setResult(null)
    setError('')
    setFilterError('')
    setExportError('')
    setFilter(next)
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!validDate(draft.from) || !validDate(draft.to) || draft.to < draft.from || draft.to > addDays(draft.from, 365)) {
      setFilterError('Chọn khoảng ngày hợp lệ, tối đa 366 ngày.')
      return
    }
    apply({ ...draft })
  }

  async function download() {
    setExporting(true)
    setExportError('')
    try {
      const blob = await analyticsApi.export(filter)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `analytics-${filter.from}-${filter.to}.csv`
      link.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (error) { setExportError(errorMessage(error)) }
    finally { setExporting(false) }
  }

  return <div className="revenue-page">
    <PageHeader eyebrow="REVENUE ANALYTICS" title="Phân tích doanh thu" description="Theo dõi giá trị đặt phòng, thanh toán demo và mức sử dụng phòng."
      action={<Button variant="secondary" onClick={download} disabled={!result} busy={exporting}><Icon name="download" size={17} />Xuất CSV</Button>} />
    <Card className="revenue-filter"><form onSubmit={submit}>
      <Select label="Kỳ báo cáo" value={preset} onChange={event => {
        const value = event.target.value
        setPreset(value)
        if (value === 'custom') return
        const next = { ...dates(value), roomTypeId: draft.roomTypeId }
        setDraft(next)
        apply(next)
      }}>{presets.map(([value, label]) => <option value={value} key={value}>{label}</option>)}</Select>
      {preset === 'custom' && <>
        <Field type="date" label="Từ ngày" required value={draft.from} onChange={event => setDraft({ ...draft, from: event.target.value })} />
        <Field type="date" label="Đến ngày" required value={draft.to} onChange={event => setDraft({ ...draft, to: event.target.value })} />
      </>}
      <Select label="Hạng phòng" value={draft.roomTypeId ?? ''} onChange={event => {
        const next = { ...draft, roomTypeId: Number(event.target.value) || undefined }
        setDraft(next)
        if (preset !== 'custom') apply(next)
      }}><option value="">Tất cả hạng phòng</option>{types.map(type => <option key={type.id} value={type.id}>{type.name}</option>)}</Select>
      {preset === 'custom' && <Button type="submit">Áp dụng</Button>}
    </form><p className="small muted">{displayDate(filter.from)} – {displayDate(filter.to)} · Giờ Việt Nam (UTC+7)</p></Card>
    {filterError && <Alert>{filterError}</Alert>}
    {exportError && <Alert>{exportError}</Alert>}
    {typeError && <Alert>Chưa tải được hạng phòng. {typeError} <Button variant="ghost" onClick={() => setReload(value => value + 1)}>Thử lại</Button></Alert>}
    {error ? <Alert>{error} <Button variant="ghost" onClick={() => { setError(''); setReload(value => value + 1) }}>Tải lại báo cáo</Button></Alert> :
      result ? <><Kpis report={result} /><AnalyticsCharts report={result} /></> :
        <div className="revenue-kpis">{Array.from({ length: 6 }, (_, index) => <Card key={index}><Skeleton /></Card>)}</div>}
  </div>
}

function Kpis({ report }: { report: AnalyticsReport }) {
  const { totals, inventory, comparison } = report
  const value = (value: number | null, format: (value: number) => string) => value === null ? 'Không có dữ liệu' : format(value)
  const kpis = [
    { title: 'Giá trị đặt phòng xác nhận', amount: formatVnd(totals.confirmedBookingValue), hint: 'Thanh toán demo thành công trong kỳ cho đơn đang xác nhận hoặc đã hoàn tất.' },
    { title: 'Lượng thanh toán demo', amount: formatVnd(totals.demoPaymentVolume), hint: 'Mọi giao dịch demo thành công theo ngày thanh toán. Số liệu mô phỏng.',
      compare: comparison && `Kỳ trước (${displayDate(comparison.from)} – ${displayDate(comparison.to)}): ${formatVnd(comparison.demoPaymentVolume)}` },
    { title: 'Tổng đơn đặt phòng', amount: count.format(totals.reservations), hint: 'Tất cả đơn theo ngày tạo, gồm giữ chỗ và các trạng thái cuối.' },
    { title: 'Tỷ lệ đêm đã đặt (ước tính)', amount: value(inventory.bookedUtilization, percent),
      hint: `${count.format(inventory.bookedNights)} đêm đã đặt / ${count.format(inventory.estimatedAvailableNights)} đêm có thể bán ước tính từ phòng đang hoạt động và lịch chặn.` },
    { title: 'Công suất lưu trú thực tế', amount: 'Không có dữ liệu', hint: 'Cần bản ghi khách thực tế nhận và trả phòng.' },
    { title: 'ADR · Giá bình quân mỗi đêm', amount: 'Không có dữ liệu', hint: 'Cần số đêm khách thực tế ở và doanh thu đủ điều kiện.' },
    { title: 'RevPAR · Doanh thu trên phòng khả dụng', amount: 'Không có dữ liệu', hint: 'Cần doanh thu đủ điều kiện và lịch sử công suất đáng tin cậy.' },
    { title: 'Tỷ lệ hủy', amount: value(totals.cancellationRate, percent), hint: 'Đơn đã hủy chia cho tổng đơn tạo trong kỳ.' },
    { title: 'Giá trị đơn demo bình quân', amount: value(totals.averageDemoBookingValue, formatVnd), hint: 'Thanh toán demo thành công chia cho số đơn đã thanh toán demo, gồm đơn 0 ₫.' },
    { title: 'Ưu đãi hạng thành viên', amount: formatVnd(totals.membershipDiscount), hint: 'Giá trị giảm theo snapshot của đơn thanh toán demo trong kỳ.' },
    { title: 'Ưu đãi voucher', amount: formatVnd(totals.voucherDiscount), hint: 'Giá trị thực giảm theo snapshot của đơn thanh toán demo trong kỳ.' },
    { title: 'Đơn chờ thanh toán', amount: count.format(totals.pendingPayments), hint: 'Đơn tạo trong kỳ đang có trạng thái chờ thanh toán trên máy chủ.' },
    { title: 'Đơn đã xác nhận', amount: count.format(totals.confirmed), hint: 'Đơn tạo trong kỳ hiện đang ở trạng thái đã xác nhận.' },
  ]
  return <div className="revenue-kpis">{kpis.map(kpi => <Card className="revenue-kpi" key={kpi.title}>
    <h2 title={kpi.hint}>{kpi.title}</h2><strong>{kpi.amount}</strong><p className="small muted">{kpi.hint}</p>
    {kpi.compare && <small>{kpi.compare}</small>}
  </Card>)}</div>
}
