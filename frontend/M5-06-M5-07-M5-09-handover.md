# Sprint 4 — M5-06, M5-07, M5-09

Phụ thuộc PR Sprint 3 #7 — review/merge Sprint 3 trước. Khi tiếp tục ngày 12/11/2026, #7 đã được người khác merge; nhánh này đã tích hợp **origin/dev cb38a5a**. Chỉ lấy từ origin/dev, không merge trực tiếp nhánh thành viên.

## Chức năng và file chính
- M5-06: `/staff` có 8 chỉ số thật, kỳ 7/30 ngày/tháng này hoặc chọn ngày, SVG doanh thu + công suất và bảng dữ liệu thay thế. `src/features/staff/reports/ReportDashboard.tsx`, `reportApi.ts`, backend `report/ReportService.java`, `ReportMetrics.java`.
- M5-07: lịch sử lọc phòng/kỳ, tên hiển thị người tạo/mở, thời gian và lý do, chỉ đọc. Có ở `/staff/rooms` bên dưới sơ đồ tầng của dev và `/staff/room-blocks`. `reports/BlockHistory.tsx`. Giữ lịch sử từng phòng có sẵn từ dev.
- M5-09: ADMIN tải CSV tại dashboard hoặc trang xuất có sẵn `/admin/booking-export` (xem route App.tsx), qua token/blob. `reports/ReportControls.tsx`, `bookings/BookingExportPage.tsx`, backend `report/ReportCsvService.java`.

## API và quyền
Tất cả GET; tham số ngày `from`, `to` theo **[from, to)**, tối đa 92 ngày; ngày sai trả 400 INVALID_FILTER.
- `/api/v1/staff/reports/summary`: STAFF/ADMIN. Tiền là chuỗi BigDecimal; không dữ liệu khách.
- `/api/v1/staff/reports/room-block-history?roomId=`: STAFF/ADMIN; khoảng chặn giao kỳ, gồm đã mở; chỉ tên hiển thị nhân viên, không email/SĐT.
- `/api/v1/admin/reports/bookings.csv`: ADMIN. UTF-8 BOM; BOOKINGS và ROOM_BLOCKS trong một file, giá booking đã chốt, SĐT che, ngày dd/MM/yyyy HH:mm giờ Việt Nam. Bao cả block đã mở.
- SecurityConfig chỉ thêm **2 matcher tại dòng 59–60** so với dev; daily Sprint 3 hiện ở dòng 58. API chỉ đọc bằng JdbcTemplate/readOnly, summary/CSV dùng snapshot REPEATABLE_READ.
- Endpoint cũ của dev `/api/v1/admin/bookings/export.csv` vẫn được giữ tương thích, không đổi hợp đồng 366 ngày/các bộ lọc của nó. Giao diện xuất dùng endpoint mới để đáp ứng giới hạn 92 ngày và các cột yêu cầu.

## Công thức và quyết định nghiệp vụ cần Bảo Long review
1. Revenue = SUM(payment_attempts.requested_amount) với status SUCCEEDED, succeeded_at trong kỳ [00:00 from, 00:00 to) +07. Không cộng theo ngày lưu trú.
2. Sold room nights = số ngày khách sạn từ ngày nhận đến trước ngày trả của booking CONFIRMED/COMPLETED, cắt theo kỳ. Giữ cả lịch sử booking của phòng hiện ngừng bán.
3. Available room nights = số phòng physical_rooms.is_active hiện tại × số ngày − số cặp (phòng, đêm) giao block chưa mở. Một đêm là 12:00–10:00 hôm sau; mỗi phòng/đêm chỉ trừ một lần, giao đúng ranh giới không trừ.
4. Occupancy = min(1, sold/available), 6 chữ số thập phân HALF_UP; mẫu số 0 → 0. RevPAR = revenue/available, 2 chữ số HALF_UP; mẫu số 0 → 0. Frontend chỉ định dạng, không tính lại tiền.
5. bookingCounts: booking tạo trong kỳ theo 5 trạng thái hiện tại. pendingHolds: PENDING_PAYMENT còn hạn **hiện tại toàn khách sạn**. completedStays: COMPLETED có endAt trong kỳ.

**Giới hạn đã ghi trên UI:** trạng thái bán/phòng và block là hiện tại, không có lịch sử bật/tắt phòng để tái dựng công suất lịch sử; block đã mở không còn trừ mẫu số. Bảo Long cần chốt chính sách này và quy ước block ngắn giao đêm, cùng việc giữ hai hợp đồng xuất CSV.

## Kiểm chứng 12/11/2026
- Backend sau tích hợp dev: `mvnw verify` **346/346**, BUILD SUCCESS local. initdb hoạt động, không cần cài VC++/đổi TEMP. Sau chỉnh CSV: chạy lại report integration + metrics **7/7**.
- Frontend: npm ci, lint, **117/117 test**, build đạt. npm ci từng bị Vite khóa DLL trên Windows, đã dừng Vite rồi chạy thành công; báo 10 moderate vulnerabilities hiện có, không tự đổi dependency.
- Edge/Playwright với API thật, fixtures tắt: dashboard đủ 8 chỉ số, kỳ sai không hiển thị số cũ, mobile 375×812 không tràn trang; lịch sử trên `/staff/rooms` thấy bản ghi chặn rồi đã mở và nhân viên; ADMIN tải CSV, STAFF không có nút và API 403, CUSTOMER 403.
- Đối chiếu kỳ 02/01/2085–03/01/2085: SQL **chỉ đọc** cho 26 phòng active, 2 phòng/đêm bị chặn, 24 khả dụng, 2 đã bán, revenue=0, occupancy=0.083333, RevPAR=0.00; API/UI khớp. Thanh toán qua API ngày hiện tại làm revenue tăng đúng **200000.00**.
- Test backend bao kỳ 1 ngày, vắt mép ngày VN, phòng ngừng bán, block, block đã mở, ngày rỗng/mẫu số 0, quyền, lọc sai, BOM, quote/comma và công thức CSV. Test frontend bao kỳ/ngày nhuận/92 ngày, token/blob và giữ nguyên byte tiền/Unicode.
- Excel thực tế đọc UTF-8 đúng tiếng Việt. Với Text Import Wizard: chọn Delimited, UTF-8 65001, Comma, text qualifier double quote. Các giá trị đầu = + - @ (kể cả sau khoảng trắng) có tiền tố `'`; không chạy thành công thức. Xuống dòng trong ô CSV chuyển thành ` ↵ ` để tránh wizard tách bản ghi; dữ liệu DB không đổi. Ảnh `06-excel-utf8.png` cho lý do nhiều dòng trong một ô.
- Ảnh trong `docs-screenshots/sprint4/`: dashboard đối chiếu, kỳ hiện tại, mobile, lịch sử đã mở, ADMIN xuất, Excel. Dữ liệu kiểm thử riêng tạo qua API, không SQL writes, không xóa dữ liệu.

## Phạm vi review
Backend diff so với dev chỉ report/test report + 2 matcher SecurityConfig. Không sửa migration hay module auth/room/booking/pricing/payment/loyalty. Các chức năng sơ đồ tầng, check-in và analytics có sẵn được giữ khi merge dev; nhiệm vụ này không thêm quy trình check-in/out. Không thêm dependency vào repo. Không approve/merge PR.
