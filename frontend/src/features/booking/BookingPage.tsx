import { Card, EmptyState, LinkButton, PageHeader } from '../../shared/ui'
import { useAuth } from '../auth/AuthProvider'

export function BookingPage() {
  const { user } = useAuth()
  return <><PageHeader eyebrow="KỲ NGHỈ CỦA BẠN" title="Tìm và đặt phòng" description="Lựa chọn thời gian nghỉ và loại phòng phù hợp." />
    <div className="booking-steps" aria-label="Các bước đặt phòng"><span className="current"><b>1</b>Chọn phòng</span><i /><span><b>2</b>Xác nhận</span><i /><span><b>3</b>Thanh toán</span></div>
    <Card><EmptyState icon="calendar" title="Chức năng đặt phòng sắp sẵn sàng"
      description={user ? 'Bạn đã đăng nhập. Chúng tôi đang chuẩn bị dịch vụ tìm phòng và đặt chỗ.' : 'Bạn có thể khám phá phòng trước. Khi giữ chỗ và thanh toán, vui lòng đăng nhập tài khoản.'}
      action={<LinkButton to={user ? '/rooms' : '/login'} variant="secondary">{user ? 'Khám phá loại phòng' : 'Đăng nhập tài khoản'}</LinkButton>} /></Card>
  </>
}
