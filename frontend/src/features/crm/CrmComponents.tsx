import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { errorMessage } from '../../shared/api/client'
import { Alert, Badge, Button, Card, Pagination, Skeleton } from '../../shared/ui'
import type { MembershipTier } from '../auth/membershipApi'
import { crmRead } from './api'
import type { Page } from './api'

export const tierNames: Record<MembershipTier, string> = { STANDARD: 'Standard', VIP: 'VIP', DIAMOND: 'Diamond' }
export const number = new Intl.NumberFormat('vi-VN')
export const missing = 'Chưa cập nhật'

export function TierBadge({ tier }: { tier: MembershipTier }) {
  return <span className={`crm-tier crm-tier--${tier.toLowerCase()}`}>{tierNames[tier]}</span>
}
export function AccountStatus({ active }: { active: boolean }) {
  return <Badge tone={active ? 'success' : 'neutral'}>{active ? 'Đang hoạt động' : 'Ngừng hoạt động'}</Badge>
}

export function CrmLoad<T>({ path, children }: { path: string; children: (data: T) => ReactNode }) {
  const [retry, setRetry] = useState(0)
  const key = `${path}:${retry}`
  const [result, setResult] = useState<{ key: string; data?: T; error?: string }>()
  useEffect(() => {
    const controller = new AbortController()
    crmRead<T>(path, controller.signal).then(data => {
      if (!controller.signal.aborted) setResult({ key, data })
    }).catch(error => {
      if (!controller.signal.aborted) setResult({ key, error: errorMessage(error) })
    })
    return () => controller.abort()
  }, [path, key])
  const current = result?.key === key ? result : undefined
  if (!current) return <Card><Skeleton lines={5} /></Card>
  if (current.error) return <Alert>{current.error} <Button variant="ghost" onClick={() => setRetry(value => value + 1)}>Thử lại</Button></Alert>
  return current.data === undefined ? null : children(current.data)
}

export function CrmPage<T>({ data, onPage, children, empty }: {
  data: Page<T>; onPage: (page: number) => void; children: ReactNode; empty: string
}) {
  return <>
    {data.items.length > 0 ? children : <p className="crm-empty muted">{empty}</p>}
    <div className="crm-page-footer"><span className="small muted">{number.format(data.totalElements)} kết quả</span>
      <Pagination page={data.page + 1} pageCount={data.totalPages} onChange={page => onPage(page - 1)} />
    </div>
  </>
}

export function CrmMetrics({ items }: { items: { label: string; value: ReactNode }[] }) {
  return <div className="crm-metrics">{items.map(({ label, value }) =>
    <Card key={label}><span className="muted">{label}</span><strong>{value}</strong></Card>)}</div>
}
