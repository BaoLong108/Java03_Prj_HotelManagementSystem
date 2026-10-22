# M5-04, M5-03: Lịch phòng và tra cứu booking cho staff

> Bản nháp, chưa tạo PR. Nhánh nguồn `DatHVT-S2`; dự kiến đích `main` sau khi PR #3 được merge và người phụ trách rebase.

## Thay đổi

Thay hai placeholder staff bằng lịch phòng 7 ngày và tra cứu booking. Lịch thể hiện booking/block theo giờ, khoảng nửa mở, cột phòng cố định, bộ lọc và chi tiết. Tra cứu mã dùng API thật đã có; form SĐT và lịch dùng DTO đề xuất khi backend chưa cung cấp endpoint. Fixture chỉ bật rõ ràng bằng `VITE_USE_FIXTURES=true`, luôn có banner, không fallback khi API lỗi.

Giữ guard STAFF/ADMIN và shared API/session hiện có; 403 về trang không đủ quyền, 401 về login. Thông tin liên hệ chỉ hiển thị dạng đã che từ server; giá hiển thị snapshot, không tính lại. Hai màn hình chỉ đọc, không có check-in/check-out. Chỉ sửa `/frontend`, không thêm dependency.

## Chạy và kiểm tra

```powershell
cd frontend
npm ci
npm test
npm run build
$env:VITE_USE_FIXTURES='false'
npm run dev -- --host localhost --port 5173 --strictPort
```

Cần backend có API tại `origin/BaoLong@2fca3e4` hoặc bản tích hợp tương đương, DB repo cổng 5433, profile dev và health UP. Nhánh frontend không merge backend đó. Đăng nhập `staff@hotel-pms.test` hoặc `admin@hotel-pms.test`, tìm mã booking đã tạo trong DB.

Để kiểm tra phần chờ backend, khởi động lại Vite với `VITE_USE_FIXTURES=true`. Lịch chọn tuần mẫu 08/10/2026; tra cứu dùng `BK-MAU-001`, `BK-MAU-003` hoặc SĐT mẫu `0900000123`. Kiểm tra desktop, 375×812, khung lịch cuộn riêng, menu, đủ status và dữ liệu đã che.

## Kết quả

- Unit: **44/44 PASS**; TypeScript + production build **PASS**.
- Cài sạch cuối: `npm ci --offline --no-audit` **PASS** (cache/lockfile nguyên vẹn). Lần npm ci thông thường ban đầu đạt; lần cuối tránh audit registry bị treo sau khi giải phóng file Vite bị Windows khóa.
- API thật: STAFF/ADMIN tra cứu mã **200**, mã không có **404 BOOKING_NOT_FOUND**, token sai **401**.
- Edge: **11/11 nhóm assertion PASS**, gồm fixture, API mã thật, guard và xử lý lỗi. Các ca HTTP giả lập được tách rõ trong bàn giao.
- 375×812: `document.documentElement.scrollWidth === 375` cho lịch fixture và tra cứu fixture/API thật, cả menu mở.
- **Chưa đạt tích hợp lịch/SĐT:** hai endpoint đề xuất hiện trả 403 cho STAFF/ADMIN. PASS kiểm tra frontend xử lý 403 không phải PASS nghiệp vụ.

## Ảnh

| Lịch mẫu | Tra cứu mã từ API thật |
| --- | --- |
| ![Lịch mẫu](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/blob/DatHVT-S2/frontend/docs-screenshots/sprint2/calendar-desktop-fixture.png?raw=true) | ![Tra cứu thật](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/blob/DatHVT-S2/frontend/docs-screenshots/sprint2/bookings-desktop-real.png?raw=true) |
| ![Lịch 375px](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/blob/DatHVT-S2/frontend/docs-screenshots/sprint2/calendar-mobile-fixture.png?raw=true) | ![Tra cứu thật 375px](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/blob/DatHVT-S2/frontend/docs-screenshots/sprint2/bookings-mobile-real.png?raw=true) |

## Reviewer cần chú ý

- M5-04 chờ Phước Sang M2-06 chốt API lịch gộp room + booking + block; chưa đủ điều kiện đóng task.
- M5-03 đã dùng endpoint mã thật. Chờ Thanh Binh M3-03 chốt tìm SĐT và roomTypeName/roomNumber/phoneMasked/emailMasked. DTO thật dùng `id` và tiền JSON number; DTO đề xuất dùng `bookingId` và decimal string.
- Không tự coi khoảng trống là availability, không tự hết hạn hold, không lộ raw PII, không tự approve/merge.
- Chi tiết request/response, môi trường, từng ca test và giới hạn tại [bàn giao](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/blob/DatHVT-S2/frontend/M5-03-M5-04-handover.md).
