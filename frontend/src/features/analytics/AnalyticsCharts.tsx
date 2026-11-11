import type { AnalyticsDay, AnalyticsReport } from './analyticsApi'
import { Card } from '../../shared/ui'
import { formatVnd } from '../booking/types'

const colors = ['#226864', '#a06628', '#4476a6', '#8d507c', '#ad5450']
const count = new Intl.NumberFormat('vi-VN')
const compact = new Intl.NumberFormat('vi-VN', { notation: 'compact', maximumFractionDigits: 1 })
type Point = { label: string; value: number }

function Chart({ title, hint, points, type, money = false, zeroIsData = false }: {
  title: string; hint: string; points: Point[]; type: 'line' | 'bars' | 'donut'; money?: boolean; zeroIsData?: boolean
}) {
  const format = money ? formatVnd : count.format
  const peak = Math.max(0, ...points.map(point => point.value))
  return <Card className="revenue-chart">
    <h2>{title}</h2><p className="muted small">{hint}</p>
    {peak === 0 && !zeroIsData ? <p className="revenue-chart-empty">Chưa có dữ liệu trong kỳ.</p> :
      type === 'line' ? <Line points={points} format={format} peak={peak} title={title} /> :
      type === 'donut' ? <Donut points={points} format={format} /> :
      <ul className="revenue-bars">{points.map(({ label, value }) => <li key={label} title={`${label}: ${format(value)}`}>
        <div><span>{label}</span><strong>{format(value)}</strong></div>
        <span className="revenue-bar-track"><span style={{ width: `${peak ? 100 * value / peak : 0}%` }} /></span>
      </li>)}</ul>}
  </Card>
}

function Line({ points, format, peak, title }: { points: Point[]; format: (value: number) => string; peak: number; title: string }) {
  const coords = points.map(({ value }, index) => ({
    x: points.length === 1 ? 280 : 56 + index * 449 / (points.length - 1), y: 146 - 125 * value / (peak || 1),
  }))
  const ticks = [0, Math.round(peak / 2), peak].filter((value, index, ticks) => ticks.indexOf(value) === index)
  return <>
    <svg viewBox="0 0 560 174" className="revenue-line" role="img" aria-label={title}>
      {ticks.map(value => {
        const y = 146 - 125 * value / (peak || 1)
        return <g key={value}><text x="0" y={y + 4}>{compact.format(value)}</text><path d={`M 49 ${y} H 513`} className="revenue-grid-line" /></g>
      })}
      <polyline points={coords.map(({ x, y }) => `${x},${y}`).join(' ')} />
      {coords.map(({ x, y }, index) => <g key={points[index].label}><circle cx={x} cy={y} r={4}><title>{points[index].label} · {format(points[index].value)}</title></circle>
        {(index === 0 || index === coords.length - 1 || (coords.length > 5 && index === Math.floor(coords.length / 2))) &&
          <text x={x} y="168" textAnchor="middle">{points[index].label}</text>}
      </g>)}
    </svg>
    <details className="revenue-values"><summary>Xem số liệu từng mốc</summary><ul>{points.map(point =>
      <li key={point.label}><span>{point.label}</span><strong>{format(point.value)}</strong></li>)}</ul></details>
  </>
}

function Donut({ points, format }: { points: Point[]; format: (value: number) => string }) {
  const total = points.reduce((sum, point) => sum + point.value, 0)
  let offset = 0
  return <div className="revenue-donut">
    <svg viewBox="0 0 128 128" role="img" aria-label={`Tổng ${format(total)} đơn`}>
      {points.map(({ label, value }, index) => {
        const fraction = 100 * value / total
        const start = offset
        offset += fraction
        return value > 0 && <circle key={label} cx="64" cy="64" r="49" pathLength="100" fill="none" stroke={colors[index]}
          strokeWidth="16" strokeDasharray={`${fraction} ${100 - fraction}`} strokeDashoffset={-start} transform="rotate(-90 64 64)">
          <title>{label} · {format(value)}</title>
        </circle>
      })}
      <text x="64" y="69" textAnchor="middle">{format(total)}</text>
    </svg>
    <ul>{points.map(({ label, value }, index) => <li key={label} title={`${label}: ${format(value)}`}>
      <span className="revenue-dot" style={{ background: colors[index] }} /><span>{label}</span><strong>{format(value)}</strong>
    </li>)}</ul>
  </div>
}

function trend(days: AnalyticsDay[], key: 'demoPaymentVolume' | 'reservations' | 'cancellations', monthly: boolean) {
  const buckets = new Map<string, number>()
  for (const day of days) {
    const bucket = monthly ? day.date.slice(0, 7) : day.date
    buckets.set(bucket, (buckets.get(bucket) ?? 0) + day[key])
  }
  return Array.from(buckets, ([key, value]) => {
    const [year, month, day] = key.split('-')
    return { label: monthly ? `${month}/${year}` : `${day}/${month}`, value }
  })
}

export function AnalyticsCharts({ report }: { report: AnalyticsReport }) {
  const { totals, days } = report
  const longPeriod = days.length > 31
  return <div className="revenue-chart-grid">
    <Chart title="Xu hướng thanh toán demo" hint={longPeriod ? 'Theo tháng phát sinh thanh toán · VND' : 'Theo ngày phát sinh thanh toán · VND'}
      points={trend(days, 'demoPaymentVolume', longPeriod)} type="line" money zeroIsData={totals.demoSettlements > 0} />
    <Chart title="Trạng thái đặt phòng" hint="Trạng thái hiện tại của các đơn được tạo trong kỳ."
      points={[
        { label: 'Chờ thanh toán', value: totals.pendingPayments }, { label: 'Đã xác nhận', value: totals.confirmed },
        { label: 'Hoàn tất', value: totals.completed }, { label: 'Đã hủy', value: totals.cancelled },
        { label: 'Hết hạn', value: totals.expired },
      ]} type="donut" />
    <Chart title="Thanh toán demo theo hạng phòng" hint="Giao dịch thành công theo hạng phòng đã đặt."
      points={report.roomTypes.map(type => ({ label: type.name, value: type.demoPaymentVolume }))} type="bars" money zeroIsData={totals.demoSettlements > 0} />
    <Chart title="Đặt phòng theo tháng" hint="Số đơn tạo trong kỳ, nhóm theo tháng."
      points={trend(days, 'reservations', true)} type="line" />
    <Chart title="Xu hướng hủy phòng" hint={longPeriod ? 'Số lượt hủy ghi nhận trong từng tháng.' : 'Số lượt hủy ghi nhận trong từng ngày.'}
      points={trend(days, 'cancellations', longPeriod)} type="line" />
    <Chart title="Phân bố hạng thành viên" hint="Hạng hiện tại của khách có đơn tạo trong kỳ, mỗi khách tính một lần."
      points={['STANDARD', 'VIP', 'DIAMOND'].map(tier => ({ label: tier, value: report.membershipDistribution[tier] ?? 0 }))} type="bars" />
  </div>
}
