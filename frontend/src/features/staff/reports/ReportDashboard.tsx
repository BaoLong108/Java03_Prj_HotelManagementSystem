import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, errorMessage } from '../../../shared/api/client'
import { Alert, Button, Card, DataTable, LoadingState } from '../../../shared/ui'
import { formatVnd } from '../bookings/bookingSearchFormat'
import { bookingStatuses, statusLabels } from '../shared/bookingStatus'
import { getSummary, presetPeriod } from './reportApi'
import type { DailyMetric, Summary } from './reportApi'
import { CsvDownload, PeriodFields } from './ReportControls'
import './reports.css'

const percent = (value: number) => new Intl.NumberFormat('vi-VN', { style: 'percent', maximumFractionDigits: 1 }).format(value)
function RevenueChart({ daily }: { daily: DailyMetric[] }) {
  // Numeric conversion is only for SVG coordinates, never for a monetary total or price.
  const max = Math.max(1, ...daily.map(d => Number(d.revenue))), step = 720 / daily.length
  const x = (i: number) => 70 + step * (i + .5)
  const line = daily.map((d, i) => `${x(i)},${230 - d.occupancyRate * 190}`).join(' ')
  return <Card className="report-chart"><h2>Doanh thu & công suất theo ngày</h2>
    <p className="chart-legend"><span>▰ Doanh thu (₫, trục trái)</span><span>━ Công suất (%, trục phải)</span></p>
    <div className="chart-scroll" role="region" aria-label="Biểu đồ doanh thu và công suất, cuộn ngang để xem" tabIndex={0}>
      <svg viewBox="0 0 860 295" role="img" aria-labelledby="report-chart-title"><title id="report-chart-title">Doanh thu thanh toán thành công và công suất từng ngày. Bảng số liệu chi tiết ở bên dưới.</title>
        {[0, .5, 1].map(n => <g key={n}><line x1="70" x2="790" y1={230 - 190 * n} y2={230 - 190 * n} stroke="#d8e0dd" /><text x="65" y={234 - 190 * n} textAnchor="end">{new Intl.NumberFormat('vi-VN', { notation: 'compact' }).format(max * n)}</text><text x="800" y={234 - 190 * n}>{n * 100}%</text></g>)}
        {daily.map((d, i) => <g key={d.date}><rect x={x(i) - step * .3} y={230 - Number(d.revenue) / max * 190} width={step * .6} height={Number(d.revenue) / max * 190} fill="#376b68"><title>{d.date}: {formatVnd(d.revenue)}</title></rect>
          {(i % Math.max(1, Math.ceil(daily.length / 10)) === 0 || i === daily.length - 1) && <text x={x(i)} y="258" textAnchor="middle">{d.date.slice(8)}/{d.date.slice(5, 7)}</text>}</g>)}
        <polyline points={line} fill="none" stroke="#b97123" strokeWidth="3" />{daily.map((d, i) => <circle key={d.date} cx={x(i)} cy={230 - d.occupancyRate * 190} r="3" fill="#b97123"><title>{d.date}: {percent(d.occupancyRate)}</title></circle>)}
      </svg>
    </div><details><summary>Xem bảng số liệu từng ngày</summary><DataTable caption="Số liệu dashboard" columns={['Ngày', 'Doanh thu', 'Đêm đã bán', 'Đêm khả dụng', 'Công suất']}>
      {daily.map(d => <tr key={d.date}><td>{d.date}</td><td>{formatVnd(d.revenue)}</td><td>{d.soldRoomNights}</td><td>{d.availableRoomNights}</td><td>{percent(d.occupancyRate)}</td></tr>)}
    </DataTable></details></Card>
}
export function ReportDashboard() {
  const [period, setPeriod] = useState(() => presetPeriod('7')), [reload, setReload] = useState(0), navigate = useNavigate()
  const key = `${period.from}|${period.to}|${reload}`
  const [state, setState] = useState<{ key: string; data?: Summary; error?: string }>({ key: '' })
  const current = state.key === key ? state : { key }
  useEffect(() => {
    const controller = new AbortController()
    Promise.resolve().then(() => getSummary({ from: period.from, to: period.to }, controller.signal)).then(data => { if (!controller.signal.aborted) setState({ key, data }) }).catch(e => {
      if (controller.signal.aborted) return
      if (e instanceof ApiError && e.status === 403) navigate('/403', { replace: true }); else setState({ key, error: errorMessage(e) })
    })
    return () => controller.abort()
  }, [period.from, period.to, key, navigate])
  const data = current.data
  const stats = data ? [
    ['Doanh thu', formatVnd(data.revenue), 'Thanh toán SUCCEEDED trong kỳ'],
    ['Đặt phòng', String(Object.values(data.bookingCounts).reduce((a, b) => a + b, 0)), 'Booking tạo trong kỳ, mọi trạng thái'],
    ['Công suất phòng', percent(data.occupancyRate), 'Đêm đã bán / đêm khả dụng, tối đa 100%'],
    ['RevPAR', formatVnd(data.revpar), 'Doanh thu / đêm phòng khả dụng, tính tại máy chủ'],
    ['Đêm phòng khả dụng', String(data.availableRoomNights), 'Phòng đang bán, trừ đêm bị chặn'],
    ['Đêm phòng đã bán', String(data.soldRoomNights), 'CONFIRMED và COMPLETED trong kỳ'],
    ['Chờ thanh toán', String(data.pendingHolds), 'Giữ chỗ còn hạn hiện tại, toàn khách sạn'],
    ['Kỳ nghỉ hoàn tất', String(data.completedStays), 'COMPLETED có giờ trả nằm trong kỳ'],
  ] : []
  return <section className="reports"><Card><div className="report-controls">
    {(['7', '30', 'month'] as const).map((p, i) => <Button key={p} variant="secondary" onClick={() => setPeriod(presetPeriod(p))}>{['7 ngày gần nhất', '30 ngày', 'Tháng này'][i]}</Button>)}
    <PeriodFields period={period} onChange={setPeriod} /><Button variant="secondary" onClick={() => setReload(n => n + 1)}>Tải lại báo cáo</Button><CsvDownload period={period} />
    </div><p className="muted">Giờ Việt Nam · Từ 00:00 ngày đầu đến trước 00:00 ngày cuối · Tối đa 92 ngày. Tháng này tính đến hết hôm nay.</p></Card>
    {current.error && <Alert>{current.error}</Alert>}{!data && !current.error && <LoadingState message="Đang tải báo cáo…" />}
    {data && <><div className="stats-grid">{stats.map(([label, value, hint]) => <Card className="stat-card" key={label}><div><span>{label}</span></div><strong>{value}</strong><p>{hint}</p></Card>)}</div>
      <p className="report-counts">{bookingStatuses.map(s => <span key={s}>{statusLabels[s]}: {data.bookingCounts[s]}</span>)}</p>
      <RevenueChart daily={data.daily} />
      <details className="report-policy"><summary>Cách tính và giới hạn số liệu</summary><p>Doanh thu theo ngày thanh toán thành công, khác ngày lưu trú. Đêm đã bán tính theo ngày nhận đến trước ngày trả. Phòng ngừng bán vẫn giữ lịch sử đêm đã bán; mẫu số chỉ dùng trạng thái phòng đang bán hiện tại.</p><p>Mỗi đêm phòng 12:00–10:00 hôm sau bị trừ một lần nếu giao với block chưa mở. Block đã mở không còn trừ mẫu số. Đây là ảnh chụp trạng thái hiện tại, không tái dựng tồn phòng lịch sử. Công suất giới hạn 0–100%; mẫu số 0 cho công suất và RevPAR bằng 0.</p></details>
    </>}
  </section>
}
