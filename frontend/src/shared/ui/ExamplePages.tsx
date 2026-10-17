import { useState } from 'react'
import { Alert, Badge, Button, Card, ConfirmDialog, DataTable, EmptyState, Field, LinkButton, LoadingState, PageHeader, Pagination, PriceDisplay, Select, Skeleton, Tabs, useToast } from './index'
import { Icon } from './Icon'
import { RoomCard } from '../../features/rooms/RoomCard'
import { BookingCard, BookingPriceSummary, HoldCountdown } from '../../features/booking/BookingComponents'
import courtyard from '../../assets/hotel-courtyard.webp'

export function NotFoundPage() {
  return <Card><PageHeader eyebrow="404" title="Không tìm thấy trang" description="Đường dẫn có thể đã thay đổi hoặc không còn tồn tại." />
    <LinkButton to="/">Về trang chủ</LinkButton></Card>
}

export function ForbiddenPage() {
  return <Card><PageHeader eyebrow="403" title="Không đủ quyền" description="Tài khoản của bạn không có quyền truy cập khu vực này." />
    <LinkButton to="/">Về trang chủ</LinkButton></Card>
}

// Developer reference, deliberately not linked from the product navigation.
export function UiKitPage() {
  const notify = useToast()
  const [confirm, setConfirm] = useState(false)
  const [tab, setTab] = useState<'loading' | 'empty'>('loading')
  const [page, setPage] = useState(1)
  const [demoExpiry] = useState(() => new Date(Date.now() + 45000).toISOString())
  return <><PageHeader eyebrow="THAM CHIẾU CHO NHÓM PHÁT TRIỂN" title="Thư viện giao diện" description="Các thành phần dùng chung trong shared/ui. Dữ liệu bên dưới chỉ là ví dụ trình bày." />
    <div className="ui-kit-grid"><Card><h2>Nút & nhãn</h2><div className="button-row"><Button onClick={() => notify('Thông báo mẫu: thao tác đã hoàn thành.')}>Thử toast <Icon name="arrow" size={16} /></Button><Button variant="secondary" onClick={() => setConfirm(true)}>Thử xác nhận</Button><Button disabled>Chưa sẵn sàng</Button></div>
      <div className="badge-row"><Badge>Trung tính</Badge><Badge tone="success">Thành công</Badge><Badge tone="warning">Đang chờ</Badge><Badge tone="danger">Đã hủy</Badge></div></Card>
      <Card><h2>Biểu mẫu</h2><div className="form-fields"><Field label="Email" type="email" placeholder="ban@example.com" hint="Gợi ý nằm bên dưới trường nhập." /><Field label="Tên khách hàng" error="Ví dụ thông báo kiểm tra dữ liệu." defaultValue="" /><Select label="Lựa chọn minh họa"><option>Ví dụ A</option><option>Ví dụ B</option></Select><Field label="Ngày minh họa" type="date" /></div></Card>
      <Card><h2>Thông báo</h2><div className="form-fields"><Alert tone="success">Thao tác đã hoàn thành.</Alert><Alert tone="warning">Bạn cần xác nhận thông tin.</Alert><Alert>Không thể xử lý yêu cầu.</Alert></div></Card>
      <Card><h2>Trạng thái tải</h2><Tabs label="Trạng thái minh họa" value={tab} onChange={setTab} items={[{ value: 'loading', label: 'Đang tải' }, { value: 'empty', label: 'Trống' }]} />{tab === 'loading' ? <><Skeleton /><LoadingState message="Ví dụ trạng thái đang tải…" /></> : <EmptyState title="Chưa có dữ liệu" description="Chỉ dùng thông báo danh sách trống sau khi API trả danh sách rỗng." />}</Card></div>
    <Card><h2>Bảng & phân trang minh họa</h2><p className="muted">Dữ liệu dưới đây chỉ để thử component tại /ui-kit.</p><DataTable caption="Bảng ví dụ" columns={['Mục minh họa', 'Giá minh họa']}><tr><td>Ví dụ ở trang {page}</td><td><PriceDisplay amount={1000000} /></td></tr></DataTable><Pagination page={page} pageCount={3} onChange={setPage} /></Card>
    <ConfirmDialog open={confirm} onClose={() => setConfirm(false)} title="Xác nhận thao tác minh họa?" description="Thao tác này chỉ hiển thị toast, không thay đổi dữ liệu nghiệp vụ." onConfirm={() => notify('Đã xác nhận ví dụ thành công.')} />
    <section className="component-examples"><PageHeader title="Component nghiệp vụ — dữ liệu minh họa" description="Chỉ là ví dụ tại UI kit. Các component này cần dữ liệu API thật trước khi dùng trong màn hình khách hoặc nhân viên." />
      <RoomCard room={{ id: 0, name: 'Loại phòng minh họa', description: 'Ví dụ bố cục thẻ phòng. Ảnh concept, không phải phòng đang bán.', maxOccupancy: 2, images: [{ src: courtyard, alt: 'Ảnh concept minh họa component' }], amenities: ['Tiện nghi minh họa'], basePricePerNight: 1000000, availability: 'unknown' }} />
      <div className="ui-kit-grid"><BookingCard code="DEMO-ONLY" roomTypeName="Đặt phòng minh họa" stayLabel="20/10/2026 – 22/10/2026" status="PENDING_PAYMENT" paymentLabel="Chưa thanh toán (ví dụ)" amountPaid={null} /><Card><h2>Báo giá minh họa từ dữ liệu truyền vào</h2><BookingPriceSummary baseAmount={2000000} discountRate={0.1} payableAmount={1800000} /></Card></div>
      <HoldCountdown bookingId={0} holdExpiresAt={demoExpiry} onElapsed={() => notify('Ví dụ hết thời gian giữ chỗ: trang thật phải xác thực lại với API.')} />
    </section>
  </>
}
