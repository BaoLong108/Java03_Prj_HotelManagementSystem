# Sprint 3 — M5-05, M5-08

## Phạm vi bàn giao
- M5-05: chặn phòng từ lịch hoặc danh sách phòng; ngày giờ +07:00, mặc định 12:00–10:00 hôm sau; xác nhận mở đúng block, khóa submit, tải lại từ API sau thành công. Giữ mục nghiệp vụ chưa hỗ trợ.
- M5-08: tab Theo ngày cạnh Tra cứu, bộ lọc trên URL, 5 trạng thái, liên kết mã booking sang lịch, loading/rỗng/lỗi, tối đa 500 kết quả và cảnh báo giới hạn. Không check-in/out.
- Backend chỉ thêm package `com.hotelpms.report` và một matcher GET trong SecurityConfig. Bảo Long cần review SQL, ranh giới ngày, dữ liệu đã che và phân quyền.

## API
- Dùng POST `/api/v1/admin/room-blocks`, DELETE `/api/v1/admin/room-blocks/{id}`, GET `/api/v1/admin/room-blocks?roomId=&history=false` có sẵn (STAFF/ADMIN).
- Thêm GET `/api/v1/staff/bookings/daily?date=YYYY-MM-DD&roomTypeId=&status=` (STAFF/ADMIN). Khoảng giao ngày `[00:00, 24:00)` Asia/Ho_Chi_Minh; mọi trạng thái; tiền là chuỗi; chỉ liên hệ đã che; 400 INVALID_FILTER; tối đa 500.
- API room hiện dùng `{error,message,...}` thay vì `{code,message,details}`. Client hỗ trợ cả hai; lỗi validation room `field: message` được gắn vào trường tương ứng. Cần Bảo Long thống nhất hợp đồng lỗi trong công việc riêng.

## Kiểm chứng 10/11/2026
- Maven verify local: **237/237 test**, BUILD SUCCESS. Lỗi initdb cũ không tái hiện; đã sửa test report thiếu profile dev, dùng cùng mật khẩu seed test của suite trên CI.
- npm ci, lint, **74/74 test**, build: đạt. npm ci báo 10 moderate vulnerabilities trong dependency hiện có; không tự đổi dependency.
- Playwright + Edge headless, API thật/Postgres 5433, fixtures tắt: tạo block → thấy trên lịch → xác nhận mở → biến mất; trùng booking đã thanh toán → đúng thông báo; daily lọc CONFIRMED/CANCELLED; CUSTOMER → /403; 375×812 không tràn ngang trang. Dữ liệu riêng tạo hoàn toàn qua API.
- Ảnh: `docs-screenshots/sprint3/01-block-calendar.png`, `02-overlap.png`, `03-daily.png`, `04-mobile.png`.
- CI bản WIP bắt được lỗi cấu hình test nói trên; kết quả cuối xem checks của PR (không dùng kết quả WIP làm kết luận).

## File chính
`src/features/staff/blocks/*`, `calendar/RoomCalendarPage.tsx`, `bookings/DailyBookings.tsx`, `bookings/dailyApi.ts`, `shared/operations.css`; backend `report/*`, test `report/ReportIntegrationTest.java`.

Không sửa module nghiệp vụ backend hay migration, không thêm dependency. Mọi kiểm tra availability/quyền do máy chủ quyết định.
