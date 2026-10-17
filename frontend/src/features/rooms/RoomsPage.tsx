import { Link, useSearchParams } from 'react-router-dom'
import { Alert, Card, EmptyState, LinkButton, PageHeader } from '../../shared/ui'
import { Icon } from '../../shared/ui/Icon'
import { StaySearchForm } from './StaySearchForm'
import { displayDate, nightsBetween, readStay, validateStay } from './search'

export function RoomsPage() {
  const [params] = useSearchParams()
  const stay = readStay(params)
  const submitted = params.has('arrival')
  const valid = Object.keys(validateStay(stay)).length === 0
  return <><PageHeader eyebrow="FIND YOUR SPACE" title="Một không gian dành cho bạn." description="Chọn thời gian, tìm loại phòng phù hợp và tận hưởng kỳ nghỉ theo cách riêng." />
    <StaySearchForm compact />
    {submitted && valid && <div className="search-summary" role="status"><Icon name="calendar" size={18} /><strong>{displayDate(stay.arrival)} — {displayDate(stay.departure)}</strong><span>{nightsBetween(stay.arrival, stay.departure)} đêm</span><span>{stay.adults} người lớn{stay.children > 0 ? ` · ${stay.children} trẻ em` : ''}</span></div>}
    {submitted && !valid && <Alert tone="warning">Lịch nghỉ trong đường dẫn chưa hợp lệ. Vui lòng chọn lại ngày đến và ngày đi.</Alert>}
    <div className="listing-layout"><aside className="listing-guide"><p className="eyebrow">KỲ NGHỈ CỦA BẠN</p><h2>Những điều cần biết</h2>
      <div><Icon name="bed" /><h3>Chọn theo loại phòng</h3><p>Phòng cụ thể sẽ được khách sạn sắp xếp khi xác nhận giữ chỗ.</p></div>
      <div><Icon name="clock" /><h3>Thời gian lưu trú</h3><p>Ngày đến từ 12:00.<br />Ngày đi trước 10:00.</p></div>
      <div><Icon name="sparkle" /><h3>Đặc quyền thành viên</h3><p>Ưu đãi theo hạng được thể hiện trong báo giá chính thức.</p><Link to="/#membership" className="text-link">Tìm hiểu thêm <Icon name="arrow" size={15} /></Link></div></aside>
      <Card className="room-results"><div className="card-heading"><h2>Danh mục loại phòng</h2><span className="muted small">Đang cập nhật</span></div><EmptyState icon="bed" title="Không gian mới đang được chuẩn bị"
        description="Danh mục phòng, giá và tiện nghi chưa sẵn sàng. Lịch nghỉ bạn chọn đã được lưu trong đường dẫn; hãy quay lại khi danh mục mở."
        action={<LinkButton to="/" variant="secondary">Khám phá khách sạn <Icon name="arrow" size={16} /></LinkButton>} /><div className="results-note"><Icon name="info" size={16} />Chưa có kết quả về phòng trống cho lịch nghỉ này.</div></Card>
    </div>
  </>
}

export function RoomDetailPage() {
  return <><PageHeader eyebrow="PHÒNG & KHÔNG GIAN" title="Chi tiết loại phòng" description="Thông tin không gian, tiện nghi và chính sách cho kỳ nghỉ." /><Card><EmptyState icon="bed" title="Chưa thể hiển thị loại phòng này" description="Danh mục phòng chưa sẵn sàng. Hiện chưa có ảnh phòng, giá hoặc tình trạng trống được xác nhận." action={<LinkButton to="/rooms">Về danh mục phòng</LinkButton>} /></Card></>
}
