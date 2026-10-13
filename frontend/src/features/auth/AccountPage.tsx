import { useAuth } from './AuthProvider'
import { roleLabels } from './types'
import { Badge, Card, LinkButton, PageHeader } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'

export function AccountPage() {
  const { user } = useAuth()
  if (!user) return null
  return <><PageHeader eyebrow="KHÔNG GIAN CỦA BẠN" title="Tài khoản" description="Thông tin đăng nhập và vai trò tài khoản của bạn." />
    <div className="account-grid"><Card><div className="profile-heading"><span className="avatar avatar--large">{user.fullName.slice(0, 1).toLocaleUpperCase('vi')}</span>
      <h2>{user.fullName}</h2><p className="muted">{user.email}</p><div className="badge-row">{user.roles.map((role) => <Badge key={role} tone="success">{roleLabels[role]}</Badge>)}</div></div>
      <dl className="details-list"><div><dt>Mã tài khoản</dt><dd>#{user.id}</dd></div><div><dt>Họ và tên</dt><dd>{user.fullName}</dd></div><div><dt>Email</dt><dd>{user.email}</dd></div><div><dt>Trạng thái phiên</dt><dd><Badge tone="success">Đã đăng nhập</Badge></dd></div></dl></Card>
      <Card className="next-stay"><span className="small-icon teal"><Icon name="bed" /></span><h2>Kỳ nghỉ tiếp theo của bạn</h2>
        <p className="muted">Khám phá các loại phòng và tìm không gian phù hợp cho chuyến đi sắp tới.</p><LinkButton to="/rooms">Khám phá phòng <Icon name="arrow" size={18} /></LinkButton></Card></div>
  </>
}
