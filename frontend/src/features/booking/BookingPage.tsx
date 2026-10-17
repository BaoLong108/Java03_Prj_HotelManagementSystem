import { useSearchParams } from 'react-router-dom'
import { Card, EmptyState, LinkButton, PageHeader, PriceDisplay, DataTable } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { useAuth } from '../auth/AuthProvider'
import { displayDate, nightsBetween, readStay, stayParams, validateStay } from '../rooms/search'

export function BookingSteps() {
  return <ol className="booking-steps" aria-label="Tiến trình đặt phòng">{['Chọn phòng', 'Xem lại', 'Thanh toán', 'Xác nhận'].map((step, index) => <li key={step} aria-current={index === 0 ? 'step' : undefined}><span>{index + 1}</span>{step}</li>)}</ol>
}

export function BookingPage() {
  const { user } = useAuth()
  const [params] = useSearchParams()
  const stay = readStay(params)
  const selected = params.has('arrival') && Object.keys(validateStay(stay)).length === 0
  return <><PageHeader eyebrow="YOUR NEXT STAY" title="Kỳ nghỉ bắt đầu từ đây." description="Chọn loại phòng và xem lại thông tin trước khi giữ chỗ." /><BookingSteps />
    <div className="booking-layout"><Card><EmptyState icon="calendar" title="Hãy bắt đầu với một không gian phù hợp" description="Dịch vụ đặt phòng đang được chuẩn bị. Bạn có thể chọn lịch nghỉ trước; chưa có phòng được giữ hoặc khoản thanh toán nào được tạo." action={<LinkButton to={`/rooms${selected ? `?${stayParams(stay)}` : ''}`}>Chọn lịch & loại phòng <Icon name="arrow" size={17} /></LinkButton>} />
      {!user && <div className="inline-note"><Icon name="user" /><div><strong>Bạn đã là thành viên?</strong><p>Đăng nhập để sẵn sàng giữ chỗ và thanh toán khi dịch vụ mở.</p></div><LinkButton to="/login" variant="secondary">Đăng nhập</LinkButton></div>}</Card>
      <Card className="booking-summary"><p className="eyebrow">THÔNG TIN KỲ NGHỈ</p><h2>Lịch trình của bạn</h2><dl className="details-list"><div><dt>Loại phòng</dt><dd>Chưa chọn</dd></div><div><dt>Ngày đến</dt><dd>{selected ? displayDate(stay.arrival) : 'Chưa chọn'}</dd></div><div><dt>Ngày đi</dt><dd>{selected ? displayDate(stay.departure) : 'Chưa chọn'}</dd></div><div><dt>Số đêm</dt><dd>{selected ? nightsBetween(stay.arrival, stay.departure) : '—'}</dd></div><div><dt>Số khách</dt><dd>{selected ? stay.adults + stay.children : '—'}</dd></div><div><dt>Giá phòng</dt><dd><PriceDisplay /></dd></div><div><dt>Ưu đãi thành viên</dt><dd>Chưa có báo giá</dd></div><div className="summary-total"><dt>Cần thanh toán</dt><dd><PriceDisplay /></dd></div></dl><p className="small muted">Số tiền chỉ hiển thị sau khi nhận báo giá xác nhận. Thời gian giữ phòng bắt đầu khi đặt chỗ thành công.</p></Card></div>
  </>
}

export function MyBookingsPage() {
  return <><PageHeader eyebrow="MY STAYS" title="Những hành trình của bạn." description="Theo dõi đặt phòng, thanh toán và thông tin lưu trú của chính bạn." action={<LinkButton to="/rooms">Lên kế hoạch kỳ nghỉ <Icon name="plus" size={17} /></LinkButton>} />
    <Card className="table-card"><div className="card-heading"><h2>Kỳ nghỉ của tôi</h2><span className="small muted">Chưa thể tải lịch sử</span></div><DataTable caption="Lịch sử đặt phòng của bạn" columns={['Đặt phòng / Loại phòng', 'Thời gian nghỉ', 'Trạng thái', 'Thanh toán', 'Đã trả']} empty={<EmptyState icon="calendar" title="Lịch sử đặt phòng chưa sẵn sàng" description="Các kỳ nghỉ và chứng từ của bạn sẽ xuất hiện khi dịch vụ lịch sử được mở. Hiện chưa thể xác định bạn có đặt phòng nào." />} /></Card>
  </>
}
