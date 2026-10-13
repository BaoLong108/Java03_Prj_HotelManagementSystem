import { Alert, Badge, Button, Card, EmptyState, Field, LinkButton, LoadingState, PageHeader } from './index'
import { Icon } from './Icon'

export function NotFoundPage() {
  return <Card><EmptyState icon="info" title="Không tìm thấy trang" description="Đường dẫn có thể đã thay đổi hoặc không còn tồn tại." action={<LinkButton to="/">Về tổng quan</LinkButton>} /></Card>
}

// Developer reference, deliberately not linked from the product navigation.
export function UiKitPage() {
  return <><PageHeader eyebrow="THAM CHIẾU CHO NHÓM PHÁT TRIỂN" title="Thư viện giao diện" description="Các thành phần dùng chung trong shared/ui. Dữ liệu bên dưới chỉ là ví dụ trình bày." />
    <div className="ui-kit-grid"><Card><h2>Nút & nhãn</h2><div className="button-row"><Button onClick={() => window.alert('Đã bấm nút mẫu')}>Nút chính <Icon name="arrow" size={16} /></Button><LinkButton to="/" variant="secondary">Điều hướng</LinkButton><Button disabled>Chưa sẵn sàng</Button></div>
      <div className="badge-row"><Badge>Trung tính</Badge><Badge tone="success">Thành công</Badge><Badge tone="warning">Đang chờ</Badge><Badge tone="danger">Đã hủy</Badge></div></Card>
      <Card><h2>Biểu mẫu</h2><div className="form-fields"><Field label="Email" type="email" placeholder="ban@example.com" hint="Gợi ý nằm bên dưới trường nhập." /><Field label="Tên khách hàng" error="Ví dụ thông báo kiểm tra dữ liệu." defaultValue="" /></div></Card>
      <Card><h2>Thông báo</h2><div className="form-fields"><Alert tone="success">Thao tác đã hoàn thành.</Alert><Alert tone="warning">Bạn cần xác nhận thông tin.</Alert><Alert>Không thể xử lý yêu cầu.</Alert></div></Card>
      <Card><h2>Trạng thái tải</h2><LoadingState message="Đang tải danh sách…" /></Card></div>
    <Card><EmptyState title="Chưa có dữ liệu" description="Dùng EmptyState khi danh sách thực sự trống hoặc dịch vụ chưa sẵn sàng." /></Card>
  </>
}
