import { Card, EmptyState, PageHeader } from '../../shared/ui'

// Operational data is intentionally empty until the backend contracts are implemented.
export function StaffPage({ page }: { page: 'calendar' | 'bookings' }) {
  return <><PageHeader eyebrow="KHÔNG GIAN NHÂN VIÊN" title={page === 'calendar' ? 'Lịch phòng' : 'Danh sách đặt phòng'}
    description="Theo dõi đặt phòng và điều phối lịch phòng tại một nơi." />
    <Card><EmptyState icon="calendar" title="Đang phát triển"
      description="Chức năng này chưa sẵn sàng. Dữ liệu sẽ hiển thị sau khi hoàn tất tích hợp dịch vụ." /></Card>
  </>
}
export function AdminPage() {
  return <><PageHeader eyebrow="KHÔNG GIAN QUẢN TRỊ" title="Quản trị khách sạn" description="Quản lý danh mục loại phòng, phòng vật lý và quyền truy cập." />
    <Card><EmptyState icon="shield" title="Khu vực quản trị đang được chuẩn bị" description="Các công cụ quản lý sẽ xuất hiện tại đây khi sẵn sàng." /></Card>
  </>
}
