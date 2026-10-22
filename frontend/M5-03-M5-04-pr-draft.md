## Thay đổi

Trên nền dev, thay hai trang staff bằng lịch phòng 7 ngày và tra cứu booking chỉ đọc. ADMIN xem phòng/loại phòng và RoomBlock từ API thật; tải block một lần mỗi phòng, giữ lỗi riêng từng hàng. STAFF/ADMIN tra cứu booking theo mã thật, mã không tồn tại hiện kết quả rỗng; trường backend chưa cấp hiển thị “—”. Giữ layout/routing hiện có của dev, không sửa backend/migration/docs chung hoặc thêm dependency.

PR này dựa trên Sprint 1 [PR #3](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/pull/3), hiện chưa merge. **Nhờ review PR #3 trước rồi review phần riêng Sprint 2.** Hai commit S1 được giữ nguyên cùng hash trên cả hai nhánh.

## Đã nối API thật

- ADMIN: `GET /api/v1/admin/rooms/types`, `GET /api/v1/admin/rooms/physical?roomTypeId=…`, `GET /api/v1/admin/room-blocks?roomId=…&history=false`.
- STAFF/ADMIN: `GET /api/v1/bookings/{bookingCode}`; 200/404 đã kiểm chứng bằng HTTP và Edge.
- Quyền STAFF đọc block đã kiểm chứng 200, nhưng danh sách phòng/loại phòng trả 403. Trang STAFF giải thích giới hạn thay vì giả dữ liệu hoặc gọi API ADMIN trái quyền.

## Còn chờ API — không nghiệm thu toàn task

- **Phước Sang/Bảo Long:** quyền hoặc API danh sách phòng/loại phòng cho STAFF. M2-06 cần lịch tổng hợp `GET /api/v1/staff/room-calendar?from=&to=&roomTypeId=…` và kiểm tra block chồng booking/hold. Chế độ thật hiện chỉ có block, luôn báo “Chưa hiển thị booking – chờ API lịch tổng hợp (M2-06, Phước Sang)”.
- **Thanh Binh:** tìm SĐT `GET /api/v1/staff/bookings/search?phone=…`, metadata phòng và liên hệ che ở server. Lựa chọn SĐT bị disabled khi fixture tắt và adapter không gọi endpoint chưa có. Raw contact nếu xuất hiện chỉ hiển thị sau khi che tại client, kèm nhắc backend che tại nguồn; nhánh phòng vệ này được test bằng unit.
- Không có thao tác hủy/xác nhận/complete/check-in/check-out, không tự quyết availability hoặc tính lại giá. **M5-03/M5-04 vẫn Bị chặn ở mức toàn task; phần frontend đã nối thật sẵn sàng review.**

## Chạy

Postgres repo dùng 5433 qua `compose.override.yaml` local đã exclude khỏi Git; container khác ở 5432 giữ nguyên. Nếu máy reviewer dùng 5432, đổi DB_URL tương ứng.

```powershell
# Gốc repo, terminal backend
$env:DB_URL='jdbc:postgresql://localhost:5433/hotel_pms'
$env:CORS_ALLOWED_ORIGINS='http://localhost:5173,http://localhost:5174'
cd backend
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'

# Terminal khác, frontend
cd frontend
npm ci
npm test
npm run lint
npm run build
$env:VITE_USE_FIXTURES='false'
npm run dev -- --host localhost --port 5173 --strictPort
```

Đăng nhập seed ADMIN/STAFF theo README. ADMIN xem lịch, STAFF thấy giới hạn danh mục. Tra cứu mã có trong DB tại `/staff/bookings`. Bộ test local tạo `BK261022-80A81E`, loại phòng 6, phòng 16/17, block 3/4 qua API; mã này chỉ có trong DB local.

Fixture: terminal khác đặt `$env:VITE_USE_FIXTURES='true'`, chạy Vite cổng 5174. Chọn tuần mẫu 08/10/2026; tìm `BK-MAU-001` hoặc SĐT `0900000123`. Luôn có banner dữ liệu mẫu, không fallback tự động. Auth vẫn là API thật.

## Kiểm chứng

- `npm ci` PASS; **61/61 unit PASS**, lint PASS, TypeScript/Vite build PASS.
- **22/22 kiểm tra HTTP thật** khớp kỳ vọng (gồm lỗi 403/404/401 mong đợi); tạo dữ liệu qua API, không chèn SQL.
- **8/8 nhóm Playwright + Edge PASS**: 5 nhóm auth/API thật (1 nhóm chỉ xác nhận giới hạn STAFF), 2 nhóm fixture, 1 nhóm giả lập HTTP 500 từng phòng. Mobile 375×812 có `scrollWidth = 375`.
- Backend dev health UP, Flyway chạy migrations sẵn có; profile dev vẫn bật pricing stub ports, không coi là nghiệm thu payment/pricing đầy đủ.
- Chi tiết API, request/response, DTO đề xuất và mức đạt: [bàn giao](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/blob/DatHVT-S2/frontend/M5-03-M5-04-handover.md).

## Ảnh

**API thật — ADMIN phòng/block và STAFF tra cứu mã:**

![Lịch ADMIN API thật](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/blob/DatHVT-S2/frontend/docs-screenshots/sprint2/dev-calendar-admin-real.png?raw=true)
![Tra cứu STAFF API thật 375px](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/blob/DatHVT-S2/frontend/docs-screenshots/sprint2/dev-bookings-staff-mobile-real.png?raw=true)

**Dữ liệu mẫu — chưa nghiệm thu API lịch tổng hợp:**

![Lịch dữ liệu mẫu](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/blob/DatHVT-S2/frontend/docs-screenshots/sprint2/dev-calendar-staff-du-lieu-mau.png?raw=true)
