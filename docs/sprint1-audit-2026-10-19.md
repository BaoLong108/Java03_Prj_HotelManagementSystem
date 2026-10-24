# Audit Sprint 1 trên origin/dev

Ngày audit: 19/10/2026. Người thực hiện: Nguyễn Ngọc Bảo Long.

## Nguồn và phạm vi

- Đã fetch `origin/dev`: `cd8bb398283e16d40f4b22ece23d5f84cdaf740f`.
- WBS chính thức: `outputs/01a11bd1-09bd-7d42-a507-ef5e453c6794/Ke_hoach_PMS_4_sprint_theo_tuan.xlsx`, sheet **Sprint 1**, A11:L26 (16 task, tuần 12–18/10/2026).
- Trạng thái dưới đây đánh giá baseline `origin/dev` trước các sửa đổi của Long trên BaoLong. Không lấy trạng thái cũ trong Excel làm bằng chứng đã hoàn thành. File Excel giữ nguyên.
- [CI baseline](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/actions/runs/39028472180) PASS cho đúng SHA trên: Maven verify/startup PostgreSQL và frontend build/lint/test. Baseline có 141 backend tests; frontend được chạy lại trong audit: 36/36 PASS.
- DONE: tiêu chí task có bằng chứng trong source/schema/test. PARTIAL: có đầu ra nhưng còn thiếu hoặc contract chưa khớp. NOT DONE: chưa có implementation đáp ứng task. BLOCKED: phần đã có không thể nghiệm thu do phụ thuộc cụ thể chưa có.

Các đường dẫn bằng chứng tính từ gốc repo. Audit không triển khai bổ sung business logic của các thành viên khác.

## Kết quả đủ 16 task

| WBS / dòng | Chủ sở hữu | Task | Kết quả trên origin/dev | Bằng chứng và việc còn lại |
| --- | --- | --- | --- | --- |
| M1-01 / 11 | Bảo Long | Nền tảng, migration, CI | DONE | `backend/pom.xml`, `compose.yaml`, V1/V2, `.github/workflows/ci.yml`; CI baseline khởi động API + Flyway thành công. |
| M1-02 / 12 | Bảo Long | User, Role, CustomerProfile | DONE | `auth/entity`, `auth/repository`, `DemoDataSeeder`; `AuthIntegrationTest.developmentSeedIsHashedAndIdempotent`, migration/entity validation. Guest là ẩn danh; DB chỉ CUSTOMER/STAFF/ADMIN. |
| M1-03 / 13 | Bảo Long | Register/login JWT | DONE | `AuthController`, `AuthService`, `JwtService`, `DatabaseJwtAuthenticationConverter`; 22 ca `AuthIntegrationTest` kiểm tra hash, JWT sai/hết hạn, tài khoản khóa, email trùng và Auth DTO. |
| M1-04 / 14 | Bảo Long | Tier, discount, điểm | DONE | `loyalty/LoyaltyService`, `dto/MembershipBenefits`; 36 `LoyaltyServiceTest`: ngưỡng 10m/30m, rate 0/0.10/0.15, floor 1 điểm/10k, validation tiền. Đây là calculator, không phải ghi điểm M1-06. |
| M2-01 / 15 | Phước Sang | RoomType / PhysicalRoom | DONE | `room/entity/RoomType`, `PhysicalRoom`; V1 có FK room_type_id, UNIQUE room_number và FK ghép room/type cho Booking; Hibernate validate + repository tests trong baseline. |
| M2-02 / 16 | Phước Sang | CRUD phòng | PARTIAL | `AdminRoomController`, `RoomService` đã có create/update/soft-delete và validation. Cần chốt/sửa `RoomTypeRequest.code` dùng cả update nhưng service không cập nhật code; duplicate check chạy trước strip/uppercase trong create. Đây là phát hiện từ source, cần Sang tái hiện và nghiệm thu các ca invalid/duplicate; Long không sửa CRUD. |
| M1-05 / 17 | Bảo Long | RBAC và kiểm thử quyền | PARTIAL | Baseline `SecurityConfig` chỉ cho ADMIN vào RoomBlock; `AdminRoomBlockController` hardcode actor=1; matcher Booking mở mọi thao tác cho authenticated và confirm không kiểm tra owner. Policy/tests Auth đã có nhưng chưa bảo vệ đủ API thật. Long sửa phần security này trên BaoLong, không đổi kết quả baseline trong bảng. |
| M2-03 / 18 | Phước Sang | Mô hình RoomBlock theo khoảng | DONE | `RoomBlock`, `RoomBlockRepository`, V1 lưu start/end/reason/actor/release và EXCLUDE khoảng `[start,end)`. Mô hình block theo khoảng, không dùng một cờ available cho mọi ngày. Kiểm tra block đụng booking là phạm vi M2-06 Sprint 2. |
| M3-01 / 19 | Thanh Binh | Booking và trạng thái | DONE | `booking/entity/Booking`, `BookingStatus`, V1; có roomTypeId/physicalRoomId nội bộ, snapshot tiền, holdExpiresAt và đủ 5 trạng thái; `BookingRepositoryTest` kiểm tra persistence/constraints. |
| M2-04 / 20 | Phước Sang | Query/khóa phòng khi hold | DONE | Đầu ra đã nằm trong baseline ở `booking/service/RoomAllocationService.findAndLockAvailablePhysicalRoom`: FOR UPDATE rồi kiểm tra overlap booking/hold/block; caller `BookingService.createBooking` có transaction. Ghi nhận helper đã có dù đang đặt ở module Booking; không viết lại hoặc nghiệm thu toàn bộ tính năng Sprint 2. |
| M3-02 / 21 | Thanh Binh | API tìm loại phòng trống theo khoảng | NOT DONE | `RoomController` chỉ list/detail catalog; `BookingController` không có endpoint search/availability. `activeRoomCount` của catalog đếm phòng active, không trừ booking/hold/block theo khoảng. Helper allocation không thay thế public search API. |
| M4-01 / 22 | Đình Đạt | Contract quote / giá cơ bản | PARTIAL | `QuoteRequest`, `QuoteResponse`, `RoomTypePricingPort`, `LoyaltyPricingPort` và tài liệu đã có. Cần thống nhất với Booking: số đêm theo timezone, kiểu JSON tiền và dữ liệu handoff; tài liệu nói string nhưng record hiện dùng BigDecimal mặc định JSON number. Adapter Room thật chưa có. |
| M4-02 / 23 | Đình Đạt | Quote / snapshot giảm giá | PARTIAL | `QuoteService` và 21 unit tests có tính giá/discount; Booking có snapshot riêng. Dev vẫn dùng Room stub trả 404, Loyalty stub STANDARD; chưa có luồng quote thật từ Room/Loyalty và handoff thống nhất vào Booking. Chưa nghiệm thu báo giá tích hợp. |
| M5-01 / 24 | Thông Đạt | React/router/API client | DONE | `frontend/src/App.tsx`, `shared/api/client.ts`, `shared/layout`, `features/staff/StaffLayout`; CI build/lint PASS; `routes.test.mjs` kiểm tra layout, route và trang 403/404. |
| M3-05 / 25 | Thanh Binh | FE tìm phòng trống | BLOCKED | `StaySearchForm` có chọn ngày/số khách và query params, nhưng `RoomsPage` chỉ EmptyState; `roomApi` chưa được page gọi. Chờ public search API M3-02 và nối kết quả. Không coi catalog seed là kết quả availability. |
| M5-02 / 26 | Thông Đạt | Login / route theo role / logout | DONE | `AuthPage`, `AuthProvider`, `RequireAuth`, `navigation.ts`; `auth.test.mjs` + `routes.test.mjs` kiểm tra Auth contract, session, nhiều role, logout, loading, 403 và return URL. Tổng frontend 36/36 PASS. |

Tổng: **10 DONE, 4 PARTIAL, 1 NOT DONE, 1 BLOCKED**.

## Hành động sau audit

- Long: Security/RBAC, actor JWT, Loyalty integration interface và tài liệu cross-module trên BaoLong. Xem [contract Sprint 2](sprint2-long-integration-contracts.md).
- Sang: hoàn thiện/nghiệm thu M2-02, thống nhất Room adapter; các quy tắc nghiệp vụ M2-06 thực hiện khi bắt đầu Sprint 2.
- Binh: public search M3-02 và FE M3-05 còn thiếu từ Sprint 1; chưa giao Long làm thay.
- Đình Đạt: chốt các khác biệt quote/snapshot với Binh và đọc contract Loyalty trước khi nối adapter.
- Thông Đạt: giữ FE/Auth đã làm; chỉ nối Staff Calendar khi API tương ứng sẵn sàng.

Sprint 2 vừa bắt đầu, các thành viên khác chưa triển khai Sprint 2 theo xác nhận của Long. Một số helper/test Booking đã có trong baseline được giữ nguyên và chạy regression; điều đó không phải nghiệm thu M3-03/M3-04/M3-07. Payment chỉ có `package-info.java`, chưa có implementation để kiểm thử idempotency. Các ca phải chạy sau được liệt kê trong [checklist nghiệm thu](sprint2-acceptance-checklist.md).
