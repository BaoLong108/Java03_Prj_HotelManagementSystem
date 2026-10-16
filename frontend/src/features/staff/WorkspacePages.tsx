import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Badge, Button, Card, DataTable, EmptyState, Field, LinkButton, PageHeader, Tabs } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import type { IconName } from '../../shared/ui/Icon'
import { useServiceHealth } from '../../shared/api/health'
import { useAuth } from '../auth/AuthProvider'
import { MembershipBenefits } from '../auth/MembershipBenefits'
import { addDays, displayDate, hotelToday, validDate } from '../rooms/search'

function ServiceStatus() {
  const { status, retry } = useServiceHealth()
  return <Card className="service-status"><div className="card-heading"><h2>Kết nối dịch vụ</h2><span className={`connection-dot ${status}`} /></div>
    <Badge tone={status === 'online' ? 'success' : status === 'offline' ? 'warning' : 'neutral'}>{status === 'online' ? 'Máy chủ đang hoạt động' : status === 'offline' ? 'Kết nối gián đoạn' : 'Đang kiểm tra'}</Badge>
    <p className="muted">{status === 'online' ? 'Dịch vụ tài khoản đang sẵn sàng. Các công cụ nghiệp vụ được mở khi có dữ liệu xác thực.' : 'Kiểm tra kết nối để sử dụng các dịch vụ đã sẵn sàng.'}</p>
    <Button variant="ghost" busy={status === 'loading'} onClick={retry}><Icon name="refresh" size={16} />Kiểm tra lại</Button></Card>
}

export function StaffPage() {
  const { user } = useAuth()
  const stats: { label: string; icon: IconName; hint: string }[] = [
    { label: 'Doanh thu', icon: 'wallet', hint: 'Tổng doanh thu được ghi nhận' }, { label: 'Đặt phòng', icon: 'calendar', hint: 'Tổng số đặt phòng' },
    { label: 'Công suất phòng', icon: 'chart', hint: 'Tỷ lệ phòng được đặt' }, { label: 'RevPAR', icon: 'building', hint: 'Doanh thu trên phòng sẵn có' },
    { label: 'Phòng khả dụng', icon: 'bed', hint: 'Theo lịch lưu trú' }, { label: 'Phòng đã đặt', icon: 'key', hint: 'Đặt phòng được xác nhận' },
    { label: 'Chờ thanh toán', icon: 'clock', hint: 'Giữ chỗ còn hiệu lực' }, { label: 'Kỳ nghỉ hoàn tất', icon: 'check', hint: 'Trạng thái COMPLETED' },
  ]
  return <><PageHeader eyebrow="PROPERTY OVERVIEW" title={`Chào ${user?.fullName.split(' ').at(-1) ?? 'bạn'}, một ngày làm việc mới.`} description="Tổng quan khách sạn và lối tắt đến những công việc hằng ngày." action={<span className="today-label"><Icon name="calendar" size={16} />{displayDate(hotelToday())}</span>} />
    <div className="workspace-banner"><div><Icon name="info" /><p>Báo cáo vận hành chưa sẵn sàng. Các chỉ số chưa có dữ liệu được hiển thị bằng dấu “—”.</p></div><Badge>Đang chuẩn bị</Badge></div>
    <div className="stats-grid">{stats.map((stat) => <Card className="stat-card" key={stat.label}><div><span>{stat.label}</span><Icon name={stat.icon} size={18} /></div><strong aria-label="Chưa có dữ liệu">—</strong><p>{stat.hint}</p></Card>)}</div>
    <div className="workspace-dashboard-grid"><Card className="analytics-card"><div className="card-heading"><div><h2>Doanh thu & xu hướng đặt phòng</h2><p className="small muted">Tổng hợp theo kỳ báo cáo</p></div><Icon name="chart" /></div><EmptyState icon="chart" title="Biểu đồ sẽ xuất hiện khi có báo cáo" description="Chưa có số liệu doanh thu, công suất hoặc doanh thu theo loại phòng được xác nhận." /></Card>
      <ServiceStatus /></div>
    <div className="section-heading"><h2>Công việc thường dùng</h2></div><div className="quick-grid">{[
      { to: '/staff/bookings', title: 'Quản lý đặt phòng', description: 'Tra cứu và theo dõi kỳ nghỉ', icon: 'calendar' as const },
      { to: '/staff/calendar', title: 'Lịch phòng', description: 'Xem lịch theo ngày và tuần', icon: 'clock' as const },
      { to: '/staff/rooms', title: 'Tình trạng phòng', description: 'Theo dõi phòng và lịch chặn', icon: 'bed' as const },
    ].map((item) => <Link className="quick-card" to={item.to} key={item.to}><span className="small-icon"><Icon name={item.icon} /></span><div><h3>{item.title}</h3><p>{item.description}</p></div><Icon name="arrow" size={17} /></Link>)}</div>
  </>
}

export function StaffBookingsPage() {
  return <><PageHeader eyebrow="RESERVATIONS" title="Quản lý đặt phòng" description="Thông tin kỳ nghỉ và thanh toán trong cùng một không gian." action={<LinkButton to="/staff/calendar" variant="secondary"><Icon name="calendar" size={17} />Xem lịch phòng</LinkButton>} />
    <Card className="table-card"><div className="card-heading"><h2>Danh sách đặt phòng</h2><Badge>Chưa sẵn sàng</Badge></div><DataTable caption="Danh sách đặt phòng khách sạn" columns={['Mã đặt phòng', 'Khách hàng', 'Loại phòng', 'Ngày đến / đi', 'Trạng thái', 'Thanh toán']}
      empty={<EmptyState icon="calendar" title="Chưa thể tải đặt phòng" description="Dịch vụ đặt phòng chưa được mở. Tra cứu, bộ lọc và các thao tác được cấp quyền sẽ xuất hiện khi có dữ liệu." />} /></Card>
    <div className="policy-note"><Icon name="shield" /><p>Việc xác nhận, hủy hoặc thay đổi đặt phòng chỉ được thực hiện khi máy chủ cho phép đối với đặt phòng đó.</p></div>
  </>
}

export function CalendarPage() {
  const [start, setStart] = useState(hotelToday)
  const days = Array.from({ length: 7 }, (_, index) => addDays(start, index))
  return <><PageHeader eyebrow="ROOM CALENDAR" title="Một góc nhìn cho cả tuần" description="Lịch đặt phòng và khoảng chặn phòng theo thời gian lưu trú." />
    <Card className="calendar-card"><div className="calendar-toolbar"><Field label="Tuần bắt đầu từ" type="date" value={start} onChange={(event) => { if (validDate(event.target.value)) setStart(event.target.value) }} /><div className="button-row"><Button variant="secondary" onClick={() => setStart(addDays(start, -7))} aria-label="Tuần trước"><span className="rotate-arrow"><Icon name="arrow" size={17} /></span></Button><Button variant="secondary" onClick={() => setStart(hotelToday())}>Hôm nay</Button><Button variant="secondary" onClick={() => setStart(addDays(start, 7))} aria-label="Tuần sau"><Icon name="arrow" size={17} /></Button></div></div>
      <div className="calendar-week" aria-label="Các ngày trong tuần đang xem">{days.map((day) => <div key={day} className={day === hotelToday() ? 'is-today' : ''}><span>{new Intl.DateTimeFormat('vi-VN', { weekday: 'short', timeZone: 'UTC' }).format(new Date(`${day}T00:00:00Z`))}</span><strong>{day.slice(8)}</strong><small>{day.slice(5, 7)}/{day.slice(0, 4)}</small></div>)}</div>
      <EmptyState icon="calendar" title="Lịch phòng chưa có dữ liệu xác thực" description="Bạn có thể chuyển tuần để chọn khoảng xem. Danh sách phòng, booking và RoomBlock sẽ được tải khi dịch vụ lịch sẵn sàng; vùng trống không có nghĩa là còn phòng." />
    </Card></>
}

export function RoomOperationsPage() {
  return <><PageHeader eyebrow="ROOM OPERATIONS" title="Tình trạng phòng" description="Theo dõi phòng vật lý và các khoảng thời gian bị chặn." /><Card><EmptyState icon="bed" title="Danh sách phòng vận hành chưa sẵn sàng" description="Chưa có thông tin phòng hoặc lịch chặn từ máy chủ. Tình trạng khả dụng cần được xác định cho từng khoảng lưu trú." action={<LinkButton to="/staff/calendar" variant="secondary">Mở lịch phòng <Icon name="arrow" size={17} /></LinkButton>} /></Card>
    <section className="operation-availability"><h2>Các nghiệp vụ chưa được hỗ trợ</h2><p className="muted">Các công cụ dưới đây chưa có trong phiên bản khách sạn mini hiện tại.</p><div className="operation-grid">{[
      ['Check-in / Check-out', 'Chưa có quy trình nhận, trả phòng do nhân viên thực hiện.'], ['Housekeeping', 'Chưa có trạng thái dọn phòng hoặc phân công nhân viên.'], ['Folio & phụ thu', 'Chưa có sổ chi phí, quyết toán hoặc dịch vụ bổ sung.'],
    ].map(([title, description]) => <div key={title}><span className="outline-label">CHƯA HỖ TRỢ</span><h3>{title}</h3><p>{description}</p></div>)}</div></section>
  </>
}

export function AdminPage() {
  return <><PageHeader eyebrow="PROPERTY ADMINISTRATION" title="Quản trị khách sạn" description="Danh mục phòng, chính sách và cài đặt trong một không gian thống nhất." /><div className="admin-grid">{[
    { to: '/admin/rooms', icon: 'building' as const, title: 'Danh mục phòng', text: 'Loại phòng, phòng vật lý và giá cơ bản.' },
    { to: '/admin/members', icon: 'users' as const, title: 'Thành viên & chính sách', text: 'Quyền lợi theo hạng và nguyên tắc tích điểm.' },
    { to: '/admin/settings', icon: 'settings' as const, title: 'Cài đặt khách sạn', text: 'Tài khoản nhân viên và cấu hình vận hành.' },
  ].map((item) => <Link to={item.to} className="admin-card" key={item.to}><span className="small-icon"><Icon name={item.icon} size={26} /></span><h2>{item.title}</h2><p className="muted">{item.text}</p><span className="text-link">Mở khu vực <Icon name="arrow" size={17} /></span></Link>)}</div><ServiceStatus /></>
}

export function AdminRoomsPage() {
  const [tab, setTab] = useState<'types' | 'physical' | 'rates'>('types')
  return <><PageHeader eyebrow="ROOM INVENTORY" title="Danh mục phòng" description="Quản lý cấu trúc phòng và giá cơ bản của khách sạn." /><Tabs label="Danh mục quản lý" value={tab} onChange={setTab} items={[{ value: 'types', label: 'Loại phòng' }, { value: 'physical', label: 'Phòng vật lý' }, { value: 'rates', label: 'Giá cơ bản' }]} />
    <Card className="table-card"><DataTable caption={tab === 'types' ? 'Loại phòng' : tab === 'physical' ? 'Phòng vật lý' : 'Giá cơ bản'} columns={tab === 'types' ? ['Mã loại phòng', 'Tên loại phòng', 'Sức chứa', 'Trạng thái'] : tab === 'physical' ? ['Số phòng', 'Loại phòng', 'Tầng', 'Trạng thái'] : ['Loại phòng', 'Giá cơ bản', 'Cập nhật gần nhất']}
      empty={<EmptyState icon="building" title="Danh mục chưa sẵn sàng" description="Thông tin và thao tác thêm, sửa sẽ được mở khi dịch vụ danh mục được kết nối." />} /></Card></>
}

export function AdminMembersPage() {
  return <><PageHeader eyebrow="MEMBERSHIP" title="Thành viên & chính sách" description="Chính sách thành viên đã thống nhất cho Hotel PMS Mini." /><MembershipBenefits compact /><p className="membership-note">1 điểm cho mỗi 10.000 ₫ thực trả hợp lệ sau giảm giá, loại khoản hoàn tiền. Chỉ ghi nhận một lần khi kỳ nghỉ đã thanh toán và COMPLETED.</p>
    <Card><EmptyState icon="users" title="Thống kê thành viên chưa sẵn sàng" description="Danh sách khách hàng, lịch sử tích điểm và phân bố hạng sẽ hiển thị khi dịch vụ thành viên được mở." /></Card></>
}

export function AdminSettingsPage() {
  return <><PageHeader eyebrow="SETTINGS" title="Cài đặt khách sạn" description="Cấu hình và quyền truy cập cho nhóm vận hành." /><div className="settings-grid"><Card><h2>Quy tắc lưu trú hiện tại</h2><dl className="details-list"><div><dt>Phạm vi</dt><dd>Một khách sạn</dd></div><div><dt>Giờ đến</dt><dd>12:00</dd></div><div><dt>Giờ đi</dt><dd>10:00</dd></div><div><dt>Múi giờ hiển thị</dt><dd>Việt Nam (UTC+7)</dd></div><div><dt>Thời hạn giữ chỗ</dt><dd>15 phút từ máy chủ</dd></div></dl><p className="small muted">Thông tin tham chiếu theo cấu hình nghiệp vụ. Chưa hỗ trợ thay đổi trên giao diện.</p></Card>
      <Card><EmptyState icon="shield" title="Quản lý nhân viên chưa sẵn sàng" description="Thêm tài khoản, thay đổi vai trò và khóa nhân viên sẽ được mở khi có dịch vụ quản trị tài khoản." /></Card></div></>
}
