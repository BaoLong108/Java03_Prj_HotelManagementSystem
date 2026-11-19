import { useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { useLocation, useParams } from 'react-router-dom'
import { Alert, Button, Card, DataTable, Dialog, Field, LinkButton, PageHeader, PriceDisplay, Select, Tabs } from '../../shared/ui'
import { BookingStatusBadge } from '../booking/BookingComponents'
import { bookingStatusLabels, formatDateTime } from '../booking/types'
import { PaymentStatusBadge, paymentMethod } from '../transactions/TransactionDetails'
import type { Transaction } from '../transactions/api'
import { crmPath } from './api'
import type { Booking, CheckIn, CrmTransaction, Page, Point, Profile, Voucher } from './api'
import { AccountStatus, CrmLoad, CrmMetrics, CrmPage, missing, number, TierBadge, tierNames } from './CrmComponents'
import './crm.css'

const tabs = [
  { value: 'overview', label: 'Tổng quan' }, { value: 'bookings', label: 'Đặt phòng' },
  { value: 'transactions', label: 'Giao dịch' }, { value: 'loyalty', label: 'Hạng & điểm' },
  { value: 'vouchers', label: 'Voucher' }, { value: 'check-in-requests', label: 'Yêu cầu nhận phòng' },
] as const
type Tab = typeof tabs[number]['value']

export function CustomerProfilePage() {
  const { customerId = '' } = useParams()
  const { state } = useLocation()
  const previous = typeof state?.from === 'string' && /^\/admin\/members(?:\?|$)/.test(state.from) ? state.from : '/admin/members'
  return <div className="crm">
    <div className="crm-back"><LinkButton to={previous} variant="secondary">Về danh sách khách hàng</LinkButton></div>
    {!/^[1-9]\d*$/.test(customerId) ? <Alert>Mã khách hàng không hợp lệ.</Alert>
      : <CrmLoad<Profile> path={`${crmPath}/${customerId}`}>{data => <ProfileContent key={customerId} data={data} />}</CrmLoad>}
  </div>
}

function ProfileContent({ data }: { data: Profile }) {
  const [tab, setTab] = useState<Tab>('overview')
  const { customer, membership } = data
  const path = `${crmPath}/${customer.id}`
  const initials = customer.fullName.trim().split(/\s+/).slice(-2).map(word => Array.from(word)[0]).join('').toLocaleUpperCase('vi')
  return <>
    <Card className="crm-profile-header"><span className="avatar avatar--large" aria-hidden="true">{initials}</span>
      <div><PageHeader title={customer.fullName} description={`Khách hàng #${customer.id}`} />
        <div className="badge-row"><TierBadge tier={customer.tier} /><AccountStatus active={customer.active} /></div>
      </div>
    </Card>
    <Tabs items={[...tabs]} value={tab} onChange={setTab} label="Hồ sơ khách hàng" />
    {tab === 'overview' && <>
      <CrmMetrics items={[
        { label: 'Tổng đặt phòng', value: number.format(customer.bookingCount) },
        { label: 'Kỳ nghỉ hoàn tất', value: number.format(data.completedBookings) },
        { label: 'Lượt tất toán đã xác minh', value: number.format(data.verifiedPayments) },
        { label: 'Điểm khả dụng', value: number.format(membership.pointsBalance) },
      ]} />
      <div className="crm-overview"><Card><h2>Thông tin tài khoản</h2>
        <Details rows={[
          ['Mã khách hàng', `#${customer.id}`], ['Họ và tên', customer.fullName], ['Email', customer.email],
          ['Điện thoại', customer.phone || missing], ['Ngày đăng ký', formatDateTime(customer.registeredAt)],
          ['Trạng thái', <AccountStatus active={customer.active} />],
        ]} />
      </Card><Membership data={data} /></div>
    </>}
    {tab === 'bookings' && <BookingHistory path={`${path}/bookings`} />}
    {tab === 'transactions' && <TransactionHistory path={`${path}/transactions`} />}
    {tab === 'loyalty' && <><Membership data={data} /><History<Point> path={`${path}/loyalty`} title="Lịch sử điểm"
      columns={['Ghi nhận', 'Loại', 'Điểm', 'Đặt phòng', 'Voucher']}
      render={item => <tr key={`${item.type}-${item.id}`}><td>{formatDateTime(item.at)}</td>
        <td>{item.type === 'EARNED' ? 'Tích điểm' : 'Đổi voucher'}</td><td>{item.type === 'EARNED' ? '+' : '−'}{number.format(item.points)}</td>
        <td>{item.bookingCode ?? '—'}</td><td>{item.voucherCode ?? '—'}</td></tr>} /></>}
    {tab === 'vouchers' && <History<Voucher> path={`${path}/vouchers`} title="Lịch sử voucher"
      columns={['Mã', 'Gói đổi điểm', 'Ngày đổi', 'Trạng thái', 'Đặt phòng', 'Ngày sử dụng']}
      render={item => <tr key={item.id}><td>{item.code}</td><td>{number.format(item.points)} điểm <small><PriceDisplay amount={item.discount} /></small></td>
        <td>{formatDateTime(item.createdAt)}</td><td>{{ USED: 'Đã dùng', RESERVED: 'Đang giữ', AVAILABLE: 'Có thể sử dụng' }[item.status]}</td>
        <td>{item.bookingCode ?? '—'}</td><td>{formatDateTime(item.usedAt)}</td></tr>} />}
    {tab === 'check-in-requests' && <History<CheckIn> path={`${path}/check-in-requests`} title="Yêu cầu nhận phòng trực tuyến"
      columns={['Đặt phòng', 'Ngày gửi', 'Giờ đến dự kiến', 'Đến sớm', 'Trạng thái', 'Xét duyệt']}
      render={item => <tr key={item.id}><td>{item.bookingCode}</td><td>{formatDateTime(item.createdAt)}</td><td>{formatDateTime(item.estimatedArrival)}</td>
        <td>{item.early ? 'Có' : 'Không'}</td><td>{{ PENDING: 'Chờ duyệt', APPROVED: 'Đã duyệt', REJECTED: 'Từ chối', CANCELLED: 'Đã hủy' }[item.status]}</td>
        <td>{item.reviewer ?? '—'}{item.reviewedAt && <small>{formatDateTime(item.reviewedAt)}</small>}
          {item.rejectionReason && <small>{item.rejectionReason}</small>}</td></tr>} />}
  </>
}

function Details({ rows }: { rows: [string, ReactNode][] }) {
  return <dl className="crm-details">{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
}

function Membership({ data }: { data: Profile }) {
  const { membership: m } = data
  return <Card><div className="card-heading"><h2>Hạng thành viên</h2><TierBadge tier={m.tierCode} /></div>
    <Details rows={[
      ['Chi tiêu hợp lệ', <PriceDisplay amount={m.eligibleSpendTotal} />],
      ['Ưu đãi giá phòng', new Intl.NumberFormat('vi-VN', { style: 'percent' }).format(m.discountRate)],
      ['Điểm khả dụng', number.format(m.pointsBalance)], ['Điểm đã tích theo sổ', number.format(data.pointsEarned)],
      ['Điểm đã đổi voucher', number.format(data.pointsRedeemed)],
    ]} />
    {m.nextTier && m.nextTierSpend ? <div className="crm-progress"><label htmlFor="crm-progress">Tiến độ lên {tierNames[m.nextTier]}</label>
      <progress id="crm-progress" value={Math.min(m.eligibleSpendTotal, m.nextTierSpend)} max={m.nextTierSpend} />
      <span>Còn <PriceDisplay amount={m.remainingSpend} /> chi tiêu hợp lệ để lên hạng.</span>
    </div> : <p>Diamond là hạng cao nhất.</p>}
    <p className="small muted">Chi tiêu hợp lệ được ghi nhận sau kỳ nghỉ đã thanh toán và hoàn tất. Đổi voucher chỉ trừ điểm khả dụng. Hệ thống lưu hạng hiện tại, chưa có lịch sử đổi hạng.</p>
  </Card>
}

function History<T>({ path, title, columns, render, query = '' }: {
  path: string; title: string; columns: string[]; render: (item: T) => ReactNode; query?: string
}) {
  const [page, setPage] = useState(0)
  return <Card className="crm-history"><h2>{title}</h2>
    <CrmLoad<Page<T>> path={`${path}?page=${page}${query ? `&${query}` : ''}`}>{data =>
      <CrmPage data={data} onPage={setPage} empty="Chưa có dữ liệu phù hợp.">
        <DataTable caption={title} columns={columns}>{data.items.map(render)}</DataTable>
      </CrmPage>}
    </CrmLoad>
  </Card>
}

function BookingHistory({ path }: { path: string }) {
  const [query, setQuery] = useState('')
  const [invalidRange, setInvalidRange] = useState(false)
  const [selected, setSelected] = useState<Booking>()
  function filter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const from = String(form.get('from') ?? '')
    const to = String(form.get('to') ?? '')
    const invalid = Boolean(from && to && from > to)
    setInvalidRange(invalid)
    if (invalid) return
    const next = new URLSearchParams()
    for (const [key, value] of form) if (typeof value === 'string' && value) next.set(key, value)
    setQuery(next.toString())
  }
  return <>
    <Card><form className="crm-filters crm-filters--bookings" onSubmit={filter}>
      <Select name="status" label="Trạng thái đặt phòng"><option value="">Tất cả</option>
        {Object.entries(bookingStatusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select>
      <Field name="from" type="date" label="Nhận phòng từ ngày" /><Field name="to" type="date" label="Nhận phòng đến ngày"
        error={invalidRange ? 'Ngày kết thúc phải từ ngày bắt đầu trở đi.' : undefined} />
      <Button type="submit">Áp dụng</Button>
    </form></Card>
    <History<Booking> key={query} path={path} query={query} title="Lịch sử đặt phòng"
      columns={['Đặt phòng', 'Lịch lưu trú', 'Ngày tạo', 'Trạng thái', 'Thanh toán', 'Phải trả']}
      render={item => <tr key={item.bookingCode}><td><button type="button" className="crm-inline-link" onClick={() => setSelected(item)}>{item.bookingCode}</button><small>{item.roomType}</small></td>
        <td>{formatDateTime(item.startAt)}<small>{formatDateTime(item.endAt)} · {item.guests} khách</small></td>
        <td>{formatDateTime(item.createdAt)}</td><td><BookingStatusBadge status={item.status} /></td>
        <td>{item.paymentStatus ? <PaymentStatusBadge status={item.paymentStatus} /> : 'Chưa có giao dịch'}</td>
        <td><PriceDisplay amount={item.payableAmount} /></td></tr>} />
    <Dialog open={!!selected} onClose={() => setSelected(undefined)} title="Chi tiết đặt phòng" className="crm-dialog">
      {selected && <Details rows={[
        ['Mã đặt phòng', selected.bookingCode], ['Hạng phòng', selected.roomType], ['Số khách', selected.guests],
        ['Nhận phòng', formatDateTime(selected.startAt)], ['Trả phòng', formatDateTime(selected.endAt)],
        ['Ngày tạo', formatDateTime(selected.createdAt)], ['Trạng thái', <BookingStatusBadge status={selected.status} />],
        ['Thanh toán', selected.paymentStatus ? <PaymentStatusBadge status={selected.paymentStatus} /> : 'Chưa có giao dịch'],
        ['Giá phòng gốc', <PriceDisplay amount={selected.baseAmount} />],
        ['Ưu đãi hạng', <PriceDisplay amount={selected.membershipDiscount} />],
        ['Giảm voucher', <PriceDisplay amount={selected.voucherDiscount} />],
        ['Tổng phải trả', <PriceDisplay amount={selected.payableAmount} />],
      ]} />}
    </Dialog>
  </>
}

function TransactionHistory({ path }: { path: string }) {
  const [selected, setSelected] = useState<Transaction>()
  return <>
    <History<Transaction> path={path} title="Lịch sử giao dịch" columns={['Mã tham chiếu', 'Đặt phòng', 'Phương thức', 'Số tiền', 'Trạng thái', 'Khởi tạo', 'Tất toán']}
      render={item => <tr key={item.id}><td><button type="button" className="crm-inline-link" onClick={() => setSelected(item)}>{item.reference}</button></td>
        <td>{item.bookingCode}</td><td>{paymentMethod(item)}</td><td><PriceDisplay amount={item.amount} /></td>
        <td><PaymentStatusBadge status={item.status} /></td><td>{formatDateTime(item.initiatedAt)}</td>
        <td>{formatDateTime(item.settledAt)}</td></tr>} />
    <Dialog open={!!selected} onClose={() => setSelected(undefined)} title="Chi tiết giao dịch" className="crm-dialog">
      {selected && <CrmLoad<CrmTransaction> path={`${path}/${selected.id}`}>{detail =>
        <Details rows={[
          ['Mã tham chiếu', detail.transaction.reference], ['Đặt phòng', detail.transaction.bookingCode],
          ['Mã giao dịch VNPay', detail.providerTransactionId ?? '—'], ['Phương thức', paymentMethod(detail.transaction)],
          ['Trạng thái', <PaymentStatusBadge status={detail.transaction.status} />],
          ['Khởi tạo', formatDateTime(detail.transaction.initiatedAt)], ['Kết quả đã xác minh', formatDateTime(detail.gatewayVerifiedAt)],
          ['Tất toán', formatDateTime(detail.settledAt)], ['Giá phòng gốc', <PriceDisplay amount={detail.baseAmount} />],
          ['Ưu đãi hạng', <PriceDisplay amount={detail.membershipDiscount} />], ['Giảm voucher', <PriceDisplay amount={detail.voucherDiscount} />],
          ['Số tiền giao dịch', <PriceDisplay amount={detail.transaction.amount} />],
        ]} />}</CrmLoad>}
    </Dialog>
  </>
}
