import { useEffect, useState } from 'react'
import { errorMessage } from '../../shared/api/client'
import { Alert, Button, Card, DataTable, EmptyState, LoadingState, Pagination, PriceDisplay } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { membershipApi } from './membershipApi'
import type { LoyaltyHistory, MembershipSummary } from './membershipApi'
import './membership.css'
import { VoucherWallet } from './VoucherWallet'

type LoadResult<T> = { status: 'loading' } | { status: 'error'; error: string } | { status: 'success'; data: T }
const tierNames = { STANDARD: 'Standard', VIP: 'VIP', DIAMOND: 'Diamond' }

/** Mounted with the authenticated user id as key, so a new session never sees another customer's state. */
export function MembershipPanel() {
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [summary, setSummary] = useState<LoadResult<MembershipSummary>>({ status: 'loading' })
  const [history, setHistory] = useState<LoadResult<LoyaltyHistory>>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    setSummary({ status: 'loading' })
    setHistory({ status: 'loading' })
    membershipApi.summary(controller.signal).then(data => {
      if (!controller.signal.aborted) setSummary({ status: 'success', data })
    }).catch(error => {
      if (!controller.signal.aborted) setSummary({ status: 'error', error: errorMessage(error) })
    })
    membershipApi.history(page, controller.signal).then(data => {
      if (!controller.signal.aborted) setHistory({ status: 'success', data })
    }).catch(error => {
      if (!controller.signal.aborted) setHistory({ status: 'error', error: errorMessage(error) })
    })
    return () => controller.abort()
  }, [page, revision])

  function changePage(next: number) {
    setHistory({ status: 'loading' })
    setPage(next - 1)
  }

  return <>
    <Card className="loyalty-card">
      <div className="card-heading"><div><p className="eyebrow">HOTEL PMS MEMBERSHIP</p><h2>Đặc quyền của bạn</h2></div><Icon name="sparkle" size={28} /></div>
      {summary.status === 'loading' && <LoadingState message="Đang tải thông tin thành viên…" />}
      {summary.status === 'error' && <Alert>{summary.error}</Alert>}
      {summary.status === 'success' && <>
        <div className="loyalty-stats">
          <div><span>Hạng thành viên</span><strong>{tierNames[summary.data.tierCode]} · giảm {Math.round(summary.data.discountRate * 100)}%</strong></div>
          <div><span>Chi tiêu tích lũy hợp lệ</span><strong><PriceDisplay amount={summary.data.eligibleSpendTotal} /></strong></div>
          <div><span>Điểm tích lũy</span><strong>{new Intl.NumberFormat('vi-VN').format(summary.data.pointsBalance)}</strong></div>
        </div>
        {summary.data.nextTier ? <div className="membership-progress">
          <label htmlFor="membership-progress">Tiến độ lên {tierNames[summary.data.nextTier]}</label>
          <progress id="membership-progress" value={summary.data.eligibleSpendTotal} max={summary.data.nextTierSpend ?? 1} />
          <span>Còn <PriceDisplay amount={summary.data.remainingSpend} /> chi tiêu hợp lệ để lên hạng.</span>
        </div> : <p>Bạn đang ở hạng Diamond, hạng cao nhất của chương trình.</p>}
        <p>Điểm chỉ được cộng một lần sau khi kỳ nghỉ đã thanh toán và hoàn tất. Hạng mới áp dụng cho báo giá tiếp theo; giá booking cũ được giữ nguyên.</p>
      </>}
    </Card>
    <VoucherWallet pointsBalance={summary.status === 'success' ? summary.data.pointsBalance : undefined} onRedeemed={() => setRevision(value => value + 1)} />
    <Card>
      <div className="card-heading"><h2>Lịch sử tích điểm</h2><Button variant="secondary" busy={summary.status === 'loading' || history.status === 'loading'} onClick={() => setRevision(value => value + 1)}>Tải lại</Button></div>
      {history.status === 'loading' && <LoadingState message="Đang tải lịch sử tích điểm…" />}
      {history.status === 'error' && <Alert>{history.error}</Alert>}
      {history.status === 'success' && (history.data.content.length === 0
        ? <EmptyState icon="sparkle" title="Chưa có giao dịch tích điểm" description="Điểm được ghi nhận sau khi kỳ nghỉ đã thanh toán và hoàn tất." />
        : <>
          <DataTable caption="Lịch sử tích điểm của bạn" columns={['Booking', 'Chi tiêu hợp lệ', 'Điểm', 'Ghi nhận']}>
            {history.data.content.map(award => <tr key={award.bookingCode}>
              <td>{award.bookingCode}</td><td><PriceDisplay amount={award.eligibleAmount} /></td>
              <td>+{new Intl.NumberFormat('vi-VN').format(award.pointsAwarded)}</td>
              <td>{new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Asia/Ho_Chi_Minh' }).format(new Date(award.awardedAt))}</td>
            </tr>)}
          </DataTable>
          <Pagination page={history.data.page + 1} pageCount={history.data.totalPages} onChange={changePage} />
        </>)}
    </Card>
  </>
}
