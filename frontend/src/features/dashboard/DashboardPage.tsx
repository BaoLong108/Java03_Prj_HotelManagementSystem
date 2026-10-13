import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import { apiRequest } from '../../shared/api/client'
import { Badge, Button, Card, LinkButton, PageHeader } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'

export function DashboardPage() {
  const { user } = useAuth()
  const [connection, setConnection] = useState<'loading' | 'online' | 'offline'>('loading')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setConnection('loading')
    apiRequest<{ status: string }>('/actuator/health', { auth: false, signal: controller.signal })
      .then((health) => { if (!controller.signal.aborted) setConnection(health.status === 'UP' ? 'online' : 'offline') })
      .catch(() => { if (!controller.signal.aborted) setConnection('offline') })
    return () => controller.abort()
  }, [attempt])
  return <>
    <PageHeader eyebrow="MỘT NGÀY MỚI, MỘT TRẢI NGHIỆM MỚI" title={user ? `Xin chào, ${user.fullName.split(' ').filter(Boolean).at(-1)}!` : 'Chào mừng đến Hotel PMS'}
      description="Khám phá phòng và lên kế hoạch cho kỳ nghỉ của bạn."
      action={<span className="today-label"><Icon name="calendar" size={17} />{new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'Asia/Ho_Chi_Minh' }).format(new Date())}</span>} />
    <section className="welcome-banner"><div><Badge>KHÔNG GIAN NGHỈ NGƠI CỦA BẠN</Badge><h2>Một kỳ nghỉ tốt đẹp,<br />bắt đầu từ đây.</h2>
      <p>Chọn phòng phù hợp. Đặt chỗ dễ dàng.<br />Tận hưởng từng khoảnh khắc.</p><LinkButton to="/rooms">Khám phá loại phòng <Icon name="arrow" size={18} /></LinkButton></div>
      <div className="hotel-art" aria-hidden="true"><div className="art-sun" /><div className="hotel-building"><div className="hotel-sign">HOTEL</div><div className="hotel-windows">{Array.from({ length: 12 }, (_, i) => <i key={i} />)}</div><div className="hotel-door" /></div><div className="art-plant plant-left" /><div className="art-plant plant-right" /><div className="art-ground" /></div>
    </section>
    <div className="quick-grid"><Link to="/rooms" className="quick-card"><span className="small-icon teal"><Icon name="bed" /></span><div><h3>Khám phá phòng</h3><p>Tìm không gian bạn yêu thích</p></div><Icon name="arrow" size={19} /></Link>
      <Link to="/booking" className="quick-card"><span className="small-icon amber"><Icon name="calendar" /></span><div><h3>Đặt phòng</h3><p>Lên kế hoạch cho kỳ nghỉ</p></div><Icon name="arrow" size={19} /></Link>
      <Link to={user ? '/account' : '/login'} className="quick-card"><span className="small-icon violet"><Icon name="user" /></span><div><h3>{user ? 'Tài khoản của bạn' : 'Trở thành thành viên'}</h3><p>{user ? 'Xem thông tin và vai trò' : 'Bắt đầu với tài khoản của bạn'}</p></div><Icon name="arrow" size={19} /></Link></div>
    <div className="dashboard-grid"><Card><div className="card-heading"><div><p className="eyebrow">CHỈ VÀI BƯỚC ĐƠN GIẢN</p><h2>Sẵn sàng cho chuyến đi</h2></div><Icon name="key" size={24} /></div>
      <ol className="journey-list"><li><span>01</span><div><h3>Chọn loại phòng</h3><p>Khám phá không gian và tiện nghi phù hợp.</p></div></li><li><span>02</span><div><h3>Chọn thời gian nghỉ</h3><p>Kiểm tra phòng trống cho lịch trình của bạn.</p></div></li><li><span>03</span><div><h3>Xác nhận & thanh toán</h3><p>Đăng nhập để giữ chỗ và hoàn tất đặt phòng.</p></div></li></ol></Card>
      <div className="dashboard-aside"><Card className="connection-card"><div className="card-heading"><h2>Kết nối dịch vụ</h2><span className={`connection-dot ${connection}`} /></div>
        <Badge tone={connection === 'online' ? 'success' : connection === 'offline' ? 'warning' : 'neutral'}>{connection === 'online' ? 'Sẵn sàng' : connection === 'offline' ? 'Tạm gián đoạn' : 'Đang kiểm tra'}</Badge>
        <p className="muted">{connection === 'online' ? 'Máy chủ đang hoạt động. Bạn có thể đăng ký và đăng nhập tài khoản.' : connection === 'offline' ? 'Chưa kết nối được máy chủ. Hãy thử lại sau ít phút.' : 'Đang kiểm tra kết nối đến máy chủ.'}</p>
        <Button variant="ghost" onClick={() => setAttempt((value) => value + 1)} busy={connection === 'loading'}><Icon name="refresh" size={16} />Kiểm tra lại</Button></Card>
        <Card className="member-card"><Icon name="shield" size={26} /><h2>{user ? 'Tất cả ở cùng một nơi' : 'Chào bạn, vị khách mới!'}</h2><p className="muted">{user ? 'Thông tin tài khoản luôn sẵn sàng khi bạn cần.' : 'Bạn có thể khám phá phòng trước khi tạo tài khoản.'}</p><Link className="text-link" to={user ? '/account' : '/register'}>{user ? 'Xem tài khoản' : 'Tạo tài khoản miễn phí'}<Icon name="arrow" size={16} /></Link></Card></div></div>
  </>
}
