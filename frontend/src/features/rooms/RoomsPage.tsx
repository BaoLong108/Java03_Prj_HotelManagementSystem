import { BookingPage } from '../booking/BookingPage'
import { Card, EmptyState, LinkButton, PageHeader } from '../../shared/ui'

export function RoomsPage() { return <BookingPage /> }

export function RoomDetailPage() {
  return <><PageHeader eyebrow="PHÒNG & KHÔNG GIAN" title="Chi tiết loại phòng" description="Thông tin không gian, tiện nghi và chính sách cho kỳ nghỉ." /><Card><EmptyState icon="bed" title="Chưa thể hiển thị loại phòng này" description="Danh mục phòng chưa sẵn sàng. Hiện chưa có ảnh phòng, giá hoặc tình trạng trống được xác nhận." action={<LinkButton to="/rooms">Về danh mục phòng</LinkButton>} /></Card></>
}
