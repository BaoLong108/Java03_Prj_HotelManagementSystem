import { useEffect, useRef, useState } from 'react'
import { errorMessage } from '../../shared/api/client'
import { Alert, Badge, Button, Card, EmptyState, LoadingState, Pagination, PriceDisplay } from '../../shared/ui'
import { voucherApi } from './voucherApi'
import type { VoucherOffer, VoucherPage } from './voucherApi'
import './vouchers.css'

export function VoucherWallet({ pointsBalance, onRedeemed }: { pointsBalance?: number; onRedeemed: () => void }) {
  const [page, setPage] = useState(0)
  const [revision, setRevision] = useState(0)
  const [data, setData] = useState<{ offers: VoucherOffer[]; wallet: VoucherPage } | null>(null)
  const [loadError, setLoadError] = useState('')
  const [redeemError, setRedeemError] = useState('')
  const [createdCode, setCreatedCode] = useState('')
  const [busy, setBusy] = useState(false)
  const issuing = useRef(false)
  const pendingKeys = useRef(new Map<number, string>())
  const lifetime = useRef<AbortController | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    lifetime.current = controller
    return () => controller.abort()
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    setData(null); setLoadError('')
    Promise.all([voucherApi.offers(controller.signal), voucherApi.wallet(page, controller.signal)])
      .then(([offers, wallet]) => { if (!controller.signal.aborted) setData({ offers, wallet }) })
      .catch(error => { if (!controller.signal.aborted) setLoadError(errorMessage(error)) })
    return () => controller.abort()
  }, [page, revision])

  async function redeem(pointsCost: number) {
    const signal = lifetime.current?.signal
    if (issuing.current || signal?.aborted) return
    issuing.current = true; setBusy(true); setRedeemError(''); setCreatedCode('')
    // Keep the same key after a network error: retry must not spend points twice.
    const key = pendingKeys.current.get(pointsCost) ?? crypto.randomUUID()
    pendingKeys.current.set(pointsCost, key)
    try {
      const voucher = await voucherApi.redeem(pointsCost, key, signal)
      if (signal?.aborted) return
      pendingKeys.current.delete(pointsCost)
      setCreatedCode(voucher.code)
      setPage(0); setRevision(value => value + 1)
      onRedeemed()
    } catch (error) {
      if (!signal?.aborted) setRedeemError(errorMessage(error))
    } finally {
      issuing.current = false
      if (!signal?.aborted) setBusy(false)
    }
  }

  return <Card className="voucher-wallet">
    <div className="card-heading"><h2>Đổi điểm lấy mã giảm giá</h2><Button variant="secondary" disabled={busy} onClick={() => setRevision(value => value + 1)}>Tải lại mã</Button></div>
    <p className="muted">Đổi điểm thành mã của riêng bạn, dùng một lần cho một booking. Giảm tiếp trên giá phòng sau ưu đãi hạng thành viên, tối đa bằng số phải trả. Phần dư không hoàn lại.</p>
    {loadError ? <Alert>{loadError}</Alert> : !data ? <LoadingState message="Đang tải các gói đổi điểm và ví mã…" /> : <>
      <div className="voucher-offers">{data.offers.map(offer => <div key={offer.pointsCost}>
        <strong><PriceDisplay amount={offer.discountAmount} /></strong><span>{offer.pointsCost} điểm</span>
        <Button variant="secondary" busy={busy} disabled={pointsBalance === undefined || pointsBalance < offer.pointsCost} onClick={() => void redeem(offer.pointsCost)}>Đổi {offer.pointsCost} điểm</Button>
      </div>)}</div>
      <p className="voucher-note">Điểm được trừ khi đổi mã; chi tiêu tích lũy và hạng thành viên được giữ nguyên. Mã không có hạn dùng.</p>
      <h3>Mã giảm giá của tôi</h3>
      {data.wallet.content.length === 0 ? <EmptyState icon="sparkle" title="Bạn chưa đổi mã nào" description="Chọn gói phù hợp với số điểm hiện có để nhận mã giảm giá." />
        : <ul className="voucher-list">{data.wallet.content.map(voucher => <li key={voucher.code}>
          <div><code>{voucher.code}</code><strong><PriceDisplay amount={voucher.discountAmount} /></strong></div>
          <Badge tone={voucher.status === 'AVAILABLE' ? 'success' : voucher.status === 'RESERVED' ? 'warning' : 'neutral'}>
            {voucher.status === 'AVAILABLE' ? 'Có thể sử dụng' : voucher.status === 'RESERVED' ? 'Đang giữ cho booking' : 'Đã sử dụng'}
          </Badge>
        </li>)}</ul>}
      <Pagination page={data.wallet.page + 1} pageCount={data.wallet.totalPages} onChange={next => { setData(null); setPage(next - 1) }} />
    </>}
    {createdCode && <Alert tone="success">Đổi điểm thành công. Mã của bạn: <strong className="voucher-code">{createdCode}</strong>. Nhập mã ở bước thanh toán đặt phòng.</Alert>}
    {redeemError && <Alert>{redeemError} Bạn có thể thử lại gói vừa chọn để kiểm tra cùng yêu cầu.</Alert>}
  </Card>
}
