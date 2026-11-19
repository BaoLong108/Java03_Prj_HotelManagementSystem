import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Button, Card, DataTable, Field, PageHeader, PriceDisplay, Select, Tabs } from '../../shared/ui'
import { MembershipBenefits } from '../auth/MembershipBenefits'
import { formatDate } from '../booking/types'
import { crmPath } from './api'
import type { Directory } from './api'
import { AccountStatus, CrmLoad, CrmMetrics, CrmPage, missing, number, TierBadge, tierNames } from './CrmComponents'
import './crm.css'

const tabs = [{ value: 'directory', label: 'Danh sách khách hàng' }, { value: 'policy', label: 'Chính sách thành viên' }] as const

export function AdminMembersPage() {
  const [tab, setTab] = useState<'directory' | 'policy'>('directory')
  return <div className="crm">
    <PageHeader title="Thành viên" description="Quản lý hồ sơ khách hàng và xem chính sách tích điểm của khách sạn." />
    <Tabs items={[...tabs]} value={tab} onChange={setTab} label="Thành viên" />
    {tab === 'policy' ? <section aria-label="Chính sách thành viên">
      <MembershipBenefits compact />
      <p className="membership-note">Mỗi 10.000 ₫ thực trả hợp lệ được 1 điểm. Điểm và chi tiêu hợp lệ được ghi nhận sau khi kỳ nghỉ đã thanh toán và hoàn tất.</p>
    </section> : <DirectoryView />}
  </div>
}

function DirectoryView() {
  const [params, setParams] = useSearchParams()
  const filters = new URLSearchParams()
  for (const key of ['search', 'tier', 'active', 'sort', 'page']) {
    const value = params.get(key)
    if (value) filters.set(key, value)
  }
  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const next = new URLSearchParams()
    new FormData(event.currentTarget).forEach((value, key) => {
      if (typeof value === 'string' && value.trim()) next.set(key, value.trim())
    })
    setParams(next)
  }
  const search = filters.get('search') ?? ''
  const tier = filters.get('tier') ?? ''
  const active = filters.get('active') ?? ''
  const sort = filters.get('sort') ?? 'REGISTERED'
  return <>
    <Card><form className="crm-filters" key={[search, tier, active, sort].join('|')} onSubmit={apply}>
      <Field label="Tìm khách hàng" name="search" type="search" defaultValue={search} maxLength={120} placeholder="Tên, email, ID hoặc số điện thoại" />
      <Select label="Hạng thành viên" name="tier" defaultValue={tier}><option value="">Tất cả</option>
        {Object.entries(tierNames).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</Select>
      <Select label="Trạng thái tài khoản" name="active" defaultValue={active}><option value="">Tất cả</option>
        <option value="true">Đang hoạt động</option><option value="false">Ngừng hoạt động</option></Select>
      <Select label="Sắp xếp" name="sort" defaultValue={sort}><option value="REGISTERED">Đăng ký mới nhất</option>
        <option value="SPENDING">Chi tiêu cao nhất</option><option value="POINTS">Điểm cao nhất</option></Select>
      <div className="button-row"><Button type="submit">Tìm kiếm</Button><Button variant="ghost" onClick={event => {
        event.currentTarget.form?.reset()
        setParams({})
      }}>Đặt lại</Button></div>
    </form></Card>
    <CrmLoad<Directory> path={`${crmPath}?${filters}`}>{({ statistics, customers }) => <>
      <CrmMetrics items={[
        { label: 'Tổng khách hàng', value: number.format(statistics.total) },
        { label: 'Standard', value: number.format(statistics.standard) },
        { label: 'VIP', value: number.format(statistics.vip) },
        { label: 'Diamond', value: number.format(statistics.diamond) },
      ]} />
      <Card className="crm-history"><h2>Danh sách khách hàng</h2>
        <CrmPage data={customers} empty="Không tìm thấy khách hàng phù hợp." onPage={page => {
          const next = new URLSearchParams(filters); next.set('page', String(page)); setParams(next)
        }}>
          <DataTable caption="Danh sách khách hàng" columns={['Khách hàng', 'Liên hệ', 'Thành viên', 'Chi tiêu hợp lệ', 'Đặt phòng', 'Tài khoản']}>
            {customers.items.map(customer => <tr key={customer.id}>
              <td><Link className="text-link" aria-label={`Xem hồ sơ ${customer.fullName}`} to={`/admin/members/${customer.id}`}
                state={{ from: `/admin/members${filters.size ? `?${filters}` : ''}` }}>{customer.fullName}</Link><small>#{customer.id}</small></td>
              <td>{customer.email}<small>{customer.phone || missing}</small></td>
              <td><TierBadge tier={customer.tier} /><small>{number.format(customer.points)} điểm</small></td>
              <td><PriceDisplay amount={customer.eligibleSpending} /></td>
              <td>{number.format(customer.bookingCount)}</td>
              <td><AccountStatus active={customer.active} /><small>{formatDate(customer.registeredAt)}</small></td>
            </tr>)}
          </DataTable>
        </CrmPage>
      </Card>
    </>}</CrmLoad>
  </>
}
