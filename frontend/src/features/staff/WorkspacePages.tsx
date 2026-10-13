import { Badge, Card, EmptyState, PageHeader } from '../../shared/ui'

// Operational data is intentionally empty until the backend contracts are implemented.
export function StaffPage() {
  return <><PageHeader eyebrow="KHÔNG GIAN NHÂN VIÊN" title="Vận hành khách sạn" description="Theo dõi đặt phòng và điều phối lịch phòng tại một nơi." />
    <Card><div className="card-heading"><h2>Danh sách đặt phòng</h2><Badge>Chưa có dữ liệu</Badge></div>
      <div className="table-scroll"><table><thead><tr><th>Mã đặt phòng</th><th>Khách hàng</th><th>Thời gian nghỉ</th><th>Trạng thái</th></tr></thead>
        <tbody><tr><td colSpan={4}><EmptyState icon="calendar" title="Chưa có dữ liệu đặt phòng" description="Danh sách sẽ được hiển thị khi dịch vụ đặt phòng sẵn sàng." /></td></tr></tbody></table></div></Card>
  </>
}
export function AdminPage() {
  return <><PageHeader eyebrow="KHÔNG GIAN QUẢN TRỊ" title="Quản trị khách sạn" description="Quản lý danh mục loại phòng, phòng vật lý và quyền truy cập." />
    <Card><EmptyState icon="shield" title="Khu vực quản trị đang được chuẩn bị" description="Các công cụ quản lý sẽ xuất hiện tại đây khi sẵn sàng." /></Card>
  </>
}
