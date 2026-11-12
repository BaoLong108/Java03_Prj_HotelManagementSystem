import { useAuth } from './AuthProvider'
import { roleLabels } from './types'
import { Badge, Card, LinkButton, PageHeader } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { MembershipBenefits } from './MembershipBenefits'
import { MembershipPanel } from './MembershipPanel'

export function AccountPage() {
  const { user } = useAuth()
  if (!user) return null
  const customer = user.roles.includes('CUSTOMER')
  return <><PageHeader eyebrow="YOUR PERSONAL SPACE" title={`Chào ${user.fullName.split(' ').at(-1)}, thật vui khi gặp bạn.`} description="Thông tin tài khoản và những đặc quyền dành cho hành trình của bạn." />
    <div className="account-grid"><Card className="profile-card"><div className="profile-heading"><span className="avatar avatar--large">{user.fullName.slice(0, 1).toLocaleUpperCase('vi')}</span><h2>{user.fullName}</h2><p className="muted">{user.email}</p><div className="badge-row">{user.roles.map((role) => <Badge key={role}>{roleLabels[role]}</Badge>)}</div></div>
      <dl className="details-list"><div><dt>Mã tài khoản</dt><dd>#{user.id}</dd></div><div><dt>Họ và tên</dt><dd>{user.fullName}</dd></div><div><dt>Email</dt><dd>{user.email}</dd></div><div><dt>Phiên đăng nhập</dt><dd><Badge tone="success">Đang hoạt động</Badge></dd></div></dl><div className="profile-security"><Icon name="shield" /><p>Thông tin tài khoản được xác thực qua phiên đăng nhập của bạn.</p></div></Card>
      <div className="account-main">{customer ? <MembershipPanel key={user.id} /> : <Card className="next-stay"><span className="small-icon"><Icon name="grid" /></span><h2>Không gian làm việc của bạn</h2><p className="muted">Truy cập công cụ vận hành được cấp quyền cho tài khoản.</p><LinkButton to="/staff">Mở không gian quản lý <Icon name="arrow" size={17} /></LinkButton></Card>}
        <LinkButton to={customer ? '/my-bookings' : '/rooms'} variant="secondary">{customer ? 'Xem kỳ nghỉ của tôi' : 'Khám phá website khách sạn'}<Icon name="arrow" size={17} /></LinkButton></div></div>
    {customer && <section className="account-benefits"><div className="section-heading"><div><p className="eyebrow">CHÍNH SÁCH THÀNH VIÊN</p><h2>Gắn bó nhiều hơn. Nhận nhiều hơn.</h2></div></div><MembershipBenefits compact /><p className="membership-note">1 điểm / 10.000 ₫ thực trả hợp lệ sau giảm giá, không gồm khoản hoàn tiền. Đây là chính sách chương trình, không phải hạng hiện tại của tài khoản.</p></section>}
  </>
}
