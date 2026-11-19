import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { apiAssetUrl } from '../../shared/api/client'
import { Alert, Button, Card, Dialog, EmptyState, Field, LinkButton, PageHeader, Pagination, Select, Skeleton } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { BookingStatusBadge } from '../booking/BookingComponents'
import { formatDateTime, formatVnd } from '../booking/types'
import { RoomPhoto } from '../rooms/RoomCard'
import { providerLabels, transactionApi, transactionQuery, transactionStatusLabels } from './api'
import type { Filters, Transaction, TransactionPage } from './api'
import { PaymentStatusBadge, TransactionDetails } from './TransactionDetails'
import './transactions.css'

const defaultFilters: Filters = { search: '', status: '', provider: '', from: '', to: '', sort: 'NEWEST' }
const loadError = 'Không thể tải lịch sử giao dịch. Vui lòng thử lại.'

export function TransactionHistoryPage() {
  const [draft, setDraft] = useState(defaultFilters)
  const [filters, setFilters] = useState(defaultFilters)
  const [page, setPage] = useState(0)
  const [reload, setReload] = useState(0)
  const [selected, setSelected] = useState<number | null>(null)
  const [result, setResult] = useState<{ query: string; reload: number; data?: TransactionPage }>({ query: '', reload: -1 })
  const query = transactionQuery(filters, page)
  const rangeError = Boolean(draft.from && draft.to && draft.from > draft.to)
  const current = result.query === query && result.reload === reload ? result : null
  const data = current?.data

  useEffect(() => {
    const controller = new AbortController()
    transactionApi.list(query, controller.signal).then(data => {
      if (!controller.signal.aborted) setResult({ query, reload, data })
    }).catch(() => {
      if (!controller.signal.aborted) setResult({ query, reload })
    })
    return () => controller.abort()
  }, [query, reload])

  function update<K extends keyof Filters>(key: K, value: Filters[K]) {
    setDraft(previous => ({ ...previous, [key]: value }))
  }

  function apply(event: FormEvent) {
    event.preventDefault()
    if (rangeError) return
    setFilters({ ...draft, search: draft.search.trim() })
    setPage(0)
    setReload(value => value + 1)
  }

  function reset() {
    setDraft(defaultFilters)
    setFilters(defaultFilters)
    setPage(0)
    setReload(value => value + 1)
  }

  const filtered = Boolean(filters.search || filters.status || filters.provider || filters.from || filters.to)
  const pagination = result.data && result.query === transactionQuery(filters, result.data.page) ? result.data : null
  return <>
    <PageHeader title="Lịch sử giao dịch" description="Theo dõi thanh toán và trạng thái giao dịch của bạn."
      action={<LinkButton to="/account" variant="secondary">Tài khoản</LinkButton>} />
    <div className="transaction-history">
      <Card className="transaction-filter-card">
        <form className="transaction-filters" onSubmit={apply} aria-label="Lọc lịch sử giao dịch">
          <div className="transaction-search">
            <Field type="search" label="Tìm giao dịch" placeholder="Mã đặt phòng hoặc mã giao dịch" maxLength={100}
              value={draft.search} onChange={event => update('search', event.target.value)} />
          </div>
          <Select label="Trạng thái giao dịch" value={draft.status} onChange={event => update('status', event.target.value as Filters['status'])}>
            <option value="">Tất cả trạng thái</option>
            {Object.entries(transactionStatusLabels).map(([status, label]) => <option key={status} value={status}>{label}</option>)}
          </Select>
          <Select label="Nhà cung cấp thanh toán" value={draft.provider} onChange={event => update('provider', event.target.value as Filters['provider'])}>
            <option value="">Tất cả nhà cung cấp</option>
            {Object.entries(providerLabels).map(([provider, label]) => <option key={provider} value={provider}>{label}</option>)}
          </Select>
          <Field type="date" label="Từ ngày" value={draft.from} onChange={event => update('from', event.target.value)} />
          <Field type="date" label="Đến ngày" value={draft.to} onChange={event => update('to', event.target.value)}
            error={rangeError ? 'Chọn ngày kết thúc từ ngày bắt đầu trở đi.' : undefined} />
          <Select label="Sắp xếp" value={draft.sort} onChange={event => update('sort', event.target.value as Filters['sort'])}>
            <option value="NEWEST">Mới nhất</option><option value="OLDEST">Cũ nhất</option>
          </Select>
          <div className="transaction-filter-actions">
            <Button type="submit" disabled={rangeError}><Icon name="search" size={16} />Áp dụng</Button>
            <Button variant="ghost" onClick={reset}>Đặt lại</Button>
          </div>
        </form>
      </Card>

      <section aria-label="Kết quả giao dịch" aria-busy={!current || undefined}>
        {!current && <Card><Skeleton lines={5} /></Card>}
        {current && !data && <Alert>{loadError} <Button variant="ghost" onClick={() => setReload(value => value + 1)}>Thử lại</Button></Alert>}
        {data && (data.items.length ? <>
          <p className="transaction-count muted small" aria-live="polite">{data.totalElements} lượt giao dịch</p>
          <ul className="transaction-list">{data.items.map(transaction => <TransactionCard key={transaction.id}
            transaction={transaction} onDetails={() => setSelected(transaction.id)} />)}</ul>
        </> : <Card><EmptyState icon="wallet" title={data.totalElements > 0 ? 'Không có giao dịch trên trang này.'
          : filtered ? 'Không tìm thấy giao dịch phù hợp.' : 'Bạn chưa có giao dịch nào.'}
          description={filtered ? 'Bạn có thể đổi mã tìm kiếm hoặc bộ lọc.' : 'Các lượt thanh toán của bạn sẽ xuất hiện tại đây.'}
          action={filtered ? <Button variant="secondary" onClick={reset}>Xóa bộ lọc</Button> : undefined} /></Card>)}
        {pagination && <Pagination page={page + 1} pageCount={pagination.totalPages} onChange={next => setPage(next - 1)} />}
      </section>
    </div>
    <Dialog open={selected !== null} onClose={() => setSelected(null)} title="Chi tiết giao dịch" className="transaction-dialog">
      {selected !== null && <TransactionDetails key={selected} id={selected} />}
    </Dialog>
  </>
}

function TransactionCard({ transaction, onDetails }: { transaction: Transaction; onDetails: () => void }) {
  const { bookingCode, roomTypeName, coverImageUrl, reference, initiatedAt, amount, currency, status, bookingStatus, provider, method } = transaction
  return <li className="transaction-card">
    <div className="transaction-cover">
      <RoomPhoto key={coverImageUrl} src={coverImageUrl ? apiAssetUrl(coverImageUrl) : undefined} alt={`Ảnh bìa ${roomTypeName}`} thumbnail />
    </div>
    <div className="transaction-room">
      <h2>{roomTypeName}</h2>
      <p className="transaction-code">Đặt phòng <strong>{bookingCode}</strong></p>
      <p className="muted small transaction-reference">Mã tham chiếu <strong>{reference}</strong></p>
      <time className="muted small" dateTime={initiatedAt}>{formatDateTime(initiatedAt)}</time>
    </div>
    <div className="transaction-payment">
      <p className="transaction-amount">{formatVnd(amount)} <small>{currency}</small></p>
      <p className="muted small">{providerLabels[provider]}{method === 'VOUCHER' ? ' · Voucher' : ''}</p>
      <PaymentStatusBadge status={status} />
    </div>
    <div className="transaction-card-actions">
      <p className="muted small">Đặt phòng <BookingStatusBadge status={bookingStatus} /></p>
      <Button variant="secondary" onClick={onDetails} aria-label={`Xem chi tiết ${reference}`}>Xem chi tiết <Icon name="arrow" size={16} /></Button>
    </div>
  </li>
}
