# Bàn giao M5-04, M5-03 — tích hợp dev

> Bản này lưu bằng chứng lịch sử ngày 22–23/10. Ngày 26/10, Long đã bổ sung API Staff Calendar/Lookup thật và cập nhật adapter. Trạng thái/DTO hiện tại xem [Sprint 2 completion](../docs/sprint2-completion.md); các giới hạn STAFF/phone/booking và CI cũ bên dưới đã được xử lý trong bản tích hợp.

Kiểm thử local ngày **22/10/2026**, cập nhật CI ngày **23/10/2026**, múi giờ Asia/Ho_Chi_Minh. Nhánh `DatHVT-S2`, nền `origin/dev@b6bbe14`.

**Frontend sẵn sàng review; chưa đủ điều kiện đóng toàn bộ M5-04/M5-03.** ADMIN xem phòng + block thật; STAFF bị giới hạn danh sách phòng từ backend. STAFF/ADMIN tra cứu mã booking thật. Lịch booking tổng hợp và tìm SĐT vẫn chờ API.

## 1. Git và phạm vi

- PR #3 đã đổi base sang `dev` theo xác nhận của Bảo Long. Xung đột chỉ ở frontend, đã giữ layout/routing/tính năng hiện có trên dev. PR #3 tại `53d1f26` gồm hai commit còn thiếu: `0a4bbb5` tương đương `7b2853d`, `53d1f26` tương đương `c8218a6`.
- `DatHVT-S2` dùng đúng hai commit đó, bốn commit S2 cũ được rebase thành `102b82b`, `1bb692e`, `30194fe`, `7438686`; commit bàn giao là `9b8b6ba`.
- Phần nối API thật: `bbddd62`. Review PR #3 trước, rồi review phần riêng của Sprint 2. Không tự approve/merge.
- PR Sprint 2: [#4 — M5-04, M5-03: Lịch phòng và tra cứu booking cho staff](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/pull/4), base `dev`. PR #3 và #4 không xung đột tại lần kiểm tra; chưa có review phê duyệt.
- Chỉ sửa file tracked trong `frontend/`; backend, migrations, docs chung giữ nguyên theo dev. `package.json`/lockfile không đổi so với dev; không thêm dependency dự án. Playwright 1.58.2 dùng từ thư mục tạm ngoài repo.
- Các ảnh không có tiền tố `dev-` là bằng chứng lịch sử trước rebase; ảnh mới bên dưới mới phản ánh bản dev hiện tại.

## 2. API đã đối chiếu và sử dụng

Nguồn: controller/DTO/security của `origin/dev@b6bbe14`, đã kiểm chứng HTTP local.

| Endpoint | Quyền thật trên dev | Frontend dùng |
| --- | --- | --- |
| `GET /api/v1/admin/rooms/types` | ADMIN; STAFF 403 | Danh mục loại phòng và filter lịch |
| `GET /api/v1/admin/rooms/physical?roomTypeId={id}` | ADMIN; STAFF 403; roomTypeId bắt buộc | Hàng phòng vật lý, gọi một lần cho mỗi loại đang chọn |
| `GET /api/v1/admin/room-blocks?roomId={id}&history=false` | STAFF/ADMIN; CUSTOMER 403 | Một request/phòng, Promise.all, lọc block active giao khoảng `[from,to)` |
| `GET /api/v1/bookings/{bookingCode}` | STAFF/ADMIN; CUSTOMER chỉ booking của mình | Tra cứu mã; 404 BOOKING_NOT_FOUND thành kết quả rỗng |
| `POST /api/v1/admin/rooms/types`, `/physical` | ADMIN | Chỉ script chuẩn bị dữ liệu test, không có nút ghi trong UI |
| `POST /api/v1/admin/room-blocks` | STAFF/ADMIN | Chỉ tạo hai block kiểm thử qua API |
| `POST /api/v1/bookings` | CUSTOMER | Chỉ tạo một booking kiểm thử qua API |

DTO thật:

- RoomType: `id, code, name, description, maxOccupancy, basePricePerNight, activeRoomCount`. `activeRoomCount` không phải availability theo thời gian.
- PhysicalRoom: `id, roomTypeId, roomTypeCode, roomTypeName, roomNumber, floorNumber, active`.
- RoomBlock: `id, physicalRoomId, roomNumber, startAt, endAt, reason, createdByUserId, createdAt, active, releasedByUserId, releasedAt`. Không suy ra tên người tạo từ userId.
- Booking: `id, bookingCode, customerId, roomTypeId, guestCount, startAt, endAt, holdExpiresAt, status, baseAmount, discountRate, discountAmount, payableAmount` và các mốc trạng thái. Tiền Java BigDecimal hiện là JSON number; adapter đổi thành chuỗi để định dạng, không tính lại giá.
- Booking thật chưa có `roomTypeName`, `roomNumber`, liên hệ đã che: UI hiện `—`. Nếu backend bổ sung tên/số phòng, adapter giữ các trường hợp lệ. Raw fullName/customerId không đưa vào view model. Nếu API trả phone/email đầy đủ, adapter che tại client, không lưu raw contact, và hiện ghi chú yêu cầu backend che tại server; nhánh phòng vệ này mới kiểm thử bằng unit, chưa gặp trong API thật.

### M5-04

`getRealRoomCalendar()` trong `roomCalendarApi.ts` ghép dữ liệu dev thành `RoomCalendar`; khi có API tổng hợp chỉ thay hàm này. Không gọi endpoint lịch chưa tồn tại, không dựng booking giả trong chế độ thật. Lỗi block của một phòng giữ hàng phòng với nhãn chưa tải được; các phòng khác vẫn hiển thị. Lỗi 401/403 vẫn truyền ra cơ chế session/trang quyền; không biến lỗi thành dữ liệu rỗng hợp lệ.

STAFF không gọi endpoint danh mục ADMIN để tránh chuỗi lỗi 403. Trang giải thích giới hạn quyền. ADMIN có lưới thật và thông báo bắt buộc:

> Chưa hiển thị booking – chờ API lịch tổng hợp (M2-06, Phước Sang)

Giữ `[startAt,endAt)`, giờ Asia/Ho_Chi_Minh, thanh theo giờ và cột phòng cố định. Khoảng trắng không khẳng định availability. Không có thao tác check-in/check-out, sửa/xóa block hay booking.

### M5-03

Tra cứu mã dùng API thật; 404 nghiệp vụ hiện “Không tìm thấy booking”, 403 chuyển `/403`. Tìm SĐT vẫn hiện trong select nhưng disabled khi fixture tắt, kèm “Chờ API tìm theo SĐT (Thanh Binh)”. Adapter cũng từ chối SĐT và không gửi HTTP đến endpoint chưa có. Fixture bật rõ ràng mới cho thử SĐT.

## 3. Tiêu chí và mức đạt

| Tiêu chí | Kết quả | File trong frontend |
| --- | --- | --- |
| ADMIN: phòng + block thật, lý do/giờ, lọc loại, chi tiết | ĐẠT (API thật + Edge) | `src/features/staff/calendar/roomCalendarApi.ts`, `RoomCalendarPage.tsx`, `RoomCalendarGrid.tsx` |
| STAFF: lưới phòng/block thật | CHƯA ĐẠT — quyền danh sách phòng trả 403; trang phản ánh đúng hạn chế | `RoomCalendarPage.tsx` |
| Booking trên lịch thật | CHỜ API — Phước Sang M2-06; chưa có lịch tổng hợp | `roomCalendarApi.ts` |
| Lịch đầy đủ booking/block, 6 phòng, loại phòng, trạng thái | ĐẠT VỚI FIXTURE | `roomCalendar.fixture.ts`, `RoomCalendarGrid.tsx` |
| Lỗi một phòng không làm mất các phòng đã tải; 403/abort không fallback | ĐẠT unit và HTTP giả lập; chưa là nghiệm thu lỗi dịch vụ thật | `roomCalendarApi.ts`, `tests/calendar.test.mjs` |
| STAFF/ADMIN tìm mã tồn tại và mã sai | ĐẠT (API thật 200/404 + Edge) | `src/features/staff/bookings/bookingSearchApi.ts`, `BookingSearchPage.tsx` |
| Tìm SĐT, tên loại/số phòng/liên hệ đã che từ API staff | CHỜ API — Thanh Binh | `bookingSearchApi.ts`, `bookingSearchTypes.ts` |
| Tìm SĐT và đủ 5 trạng thái | ĐẠT VỚI FIXTURE | `bookingSearch.fixture.ts` |
| Che raw contact dự phòng, không tính lại tiền, không đổi trạng thái theo đồng hồ | ĐẠT unit; API thật hiện không có contact | `bookingSearchApi.ts`, `tests/bookingSearch.test.mjs` |
| CUSTOMER bị chặn trang staff và endpoint phòng/block | ĐẠT (auth/API thật + Edge) | `src/App.tsx`, RequireAuth hiện hữu |
| Desktop và 375×812 không tràn trang | ĐẠT cho các màn hình thật/fixture đã chạy; lịch STAFF chỉ kiểm chứng trang giới hạn quyền | Hai page/CSS |

## 4. Môi trường và kiểm thử mới

- Docker: `java03_prj_hotelmanagementsystem-postgres-1`, `postgres:17-alpine`, host **5433**, healthy. Giữ nguyên `viral-product-radar-postgres`, `postgres:16`, host **5432**.
- `compose.override.yaml` dùng `ports: !override` với `5433:5432`; `/compose.override.yaml` nằm trong `.git/info/exclude`, không thuộc PR.
- Backend chạy trực tiếp từ repo đã rebase dev, profile `dev`, `DB_URL=jdbc:postgresql://localhost:5433/hotel_pms`, health `UP`. Flyway tự áp dụng migration V2 sẵn có của dev; không chỉnh SQL/migration hay chèn DB thủ công. Pricing stub ports bật theo profile dev: kết quả này không nghiệm thu tích hợp pricing/payment toàn hệ thống.
- Dữ liệu tạo qua API: roomTypeId **6**, physicalRoomId **16/17**, blockId **3/4**, booking **BK261022-80A81E**. Block ngày 23/24-10, booking 25–26-10. Đây là dữ liệu test còn trong DB; hold có thể hết hạn tự động theo backend.
- Frontend API thật: 5173 (`VITE_USE_FIXTURES=false`); fixture: 5174 (`true`). CORS được đặt bằng biến môi trường khi chạy backend.

Kết quả:

- `npm ci`: PASS, 119 packages; audit tại lần chạy không báo lỗ hổng. Đây là dependency có sẵn trên dev.
- `npm test`: **61/61 PASS**, không skip; gồm test ghép lịch, lọc biên, lỗi từng phòng, quyền/abort, booking mapping, che PII, từ chối SĐT khi API thiếu và hồi quy dev.
- `npm run lint`: **PASS**. `npm run build`: **PASS**.
- HTTP API thật: **22/22 kiểm tra khớp kỳ vọng**, gồm cả 403/404/401 mong đợi. STAFF đọc danh mục 403 là bằng chứng giới hạn, không phải đạt nghiệp vụ lịch STAFF.
- Playwright **1.58.2 + Edge: 8/8 nhóm kiểm tra PASS**: 5 nhóm API thật/auth thật (trong đó 1 nhóm xác nhận giới hạn STAFF), 2 nhóm fixture STAFF/ADMIN, 1 nhóm giả lập lỗi block 500. Mỗi màn hình mobile được kiểm tra `document.documentElement.scrollWidth === 375`.
- Lượt Edge đầu dừng ở matcher `isDisabled()` cho option; đã kiểm chứng lại bằng thuộc tính DOM native `option.disabled`, chạy lại toàn bộ 8 nhóm thành công. Không sửa ứng dụng để bỏ qua kiểm tra.
- Backend/frontend của lượt kiểm thử đã dừng; không còn listener ở 8080/5173/5174. Postgres được giữ chạy. Không dừng/xóa container, không xóa dữ liệu test.

### CI backend bị chặn bởi dữ liệu thời gian trong test trên dev

Tại cùng head `a3a0672`, [lượt push 39814675931](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/actions/runs/39814675931) đạt cả backend/frontend, nhưng [lượt PR 39823555681](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/actions/runs/39823555681) lúc 00:32 ngày 23/10 giờ Việt Nam chỉ đạt frontend. Backend chạy 160 test, lỗi 2 assertion: `testCreateBooking_SuccessAndRetrieve:142` và `testCreateBooking_VIPTierDiscount:184`, kỳ vọng `2400000.0`, thực tế `1200000.0`.

Đối chiếu chỉ đọc cho thấy hai test trong `backend/src/test/java/com/hotelpms/booking/BookingIntegrationTest.java` lấy `Instant.now() + 2 ngày`, làm tròn giờ, rồi đặt end bằng start + 2 ngày - 2 giờ. `BookingService.java:56–58` tính số đêm theo chênh lệch ngày tại `Asia/Ho_Chi_Minh`. Khi test chạy lúc 00–01 giờ, khoảng dữ liệu chỉ cách **1 ngày địa phương**, trong khi assertion luôn đòi tiền 2 đêm. Ví dụ thời điểm CI trên tạo start `2026-10-25T00:00+07:00`, end `2026-10-26T22:00+07:00`: 1 đêm × 1.200.000 đúng bằng actual trong log. Đây là nguyên nhân test phụ thuộc giờ chạy, không phải thay đổi mapping giá frontend.

`git diff origin/dev HEAD -- backend .github` rỗng. Đã ghi lại để **Thanh Binh/Bảo Long** sửa dữ liệu test thành giờ check-in/check-out cố định theo múi giờ khách sạn (hoặc dùng Clock kiểm soát được), rồi kiểm tra cả biên 00–01 giờ. Không sửa backend, đổi assertion, bỏ test hay chạy lại chỉ để lấy CI xanh. PR #4 **chưa đủ điều kiện merge** cho đến khi CI được xử lý và có review.

### Request/response rút gọn (token đã che)

```http
GET /api/v1/admin/rooms/physical?roomTypeId=6
Authorization: Bearer <ADMIN_TOKEN_REDACTED>
HTTP 200
[{"id":16,"roomTypeId":6,"roomNumber":"S2-MVJQWVVQ-A","floorNumber":1,"active":true}, ...]

GET /api/v1/admin/rooms/physical?roomTypeId=6
Authorization: Bearer <STAFF_TOKEN_REDACTED>
HTTP 403
{"code":"FORBIDDEN","message":"Bạn không có quyền truy cập"}

POST /api/v1/admin/room-blocks
Authorization: Bearer <ADMIN_TOKEN_REDACTED>
{"physicalRoomId":16,"startAt":"2026-10-23T09:00:00+07:00","endAt":"2026-10-23T18:00:00+07:00","reason":"Kiểm thử API thật S2: bảo trì 1"}
HTTP 201
{"id":3,"physicalRoomId":16,"active":true, ...}

GET /api/v1/admin/room-blocks?roomId=16&history=false
Authorization: Bearer <STAFF_OR_ADMIN_TOKEN_REDACTED>
HTTP 200
[{"id":3,"physicalRoomId":16,"startAt":"2026-10-23T02:00:00Z","endAt":"2026-10-23T11:00:00Z","reason":"Kiểm thử API thật S2: bảo trì 1","active":true, ...}]

GET /api/v1/bookings/BK261022-80A81E
Authorization: Bearer <STAFF_OR_ADMIN_TOKEN_REDACTED>
HTTP 200
{"bookingCode":"BK261022-80A81E","status":"PENDING_PAYMENT","roomTypeId":6,"guestCount":2,"baseAmount":1200000.00,"payableAmount":1200000.00, ...}

GET /api/v1/bookings/S2-NOT-FOUND
Authorization: Bearer <STAFF_OR_ADMIN_TOKEN_REDACTED>
HTTP 404
{"code":"BOOKING_NOT_FOUND","message":"Không tìm thấy đơn đặt phòng"}

GET /api/v1/admin/room-blocks?roomId=16&history=false
Authorization: Bearer <CUSTOMER_TOKEN_REDACTED>
HTTP 403
{"code":"FORBIDDEN","message":"Bạn không có quyền truy cập"}
```

## 5. Chạy lại

Từ gốc repo, giữ container khác nguyên trạng. Nếu 5432 bị chiếm, dùng override local nói trên và Postgres 5433; nếu dùng 5432 thì sửa DB_URL tương ứng.

```powershell
# Terminal backend, từ gốc repo
$env:DB_URL='jdbc:postgresql://localhost:5433/hotel_pms'
$env:CORS_ALLOWED_ORIGINS='http://localhost:5173,http://localhost:5174'
cd backend
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'

# Terminal frontend khác, từ gốc repo
cd frontend
npm ci
npm test
npm run lint
npm run build
$env:VITE_USE_FIXTURES='false'
npm run dev -- --host localhost --port 5173 --strictPort
```

Dùng tài khoản seed `admin@hotel-pms.test`, `staff@hotel-pms.test`, `customer@hotel-pms.test`, mật khẩu dev theo README. ADMIN mở `/staff/calendar?from=2026-10-22&roomTypeId=6`; STAFF thấy giới hạn quyền. Cả ADMIN/STAFF mở `/staff/bookings` và tìm mã test nêu trên hoặc booking thực tế trong DB của người chạy.

Fixture: terminal frontend riêng, đặt `$env:VITE_USE_FIXTURES='true'` rồi chạy Vite cổng 5174. Đăng nhập thật, mở lịch tuần `2026-10-08`; tra cứu `BK-MAU-001` hoặc SĐT `0900000123`. Banner “Đang dùng dữ liệu mẫu – chưa nối API thật” luôn hiện. Tắt fixture/khởi động lại khi quay về API thật; không có fallback tự động.

## 6. Ảnh mới đã kiểm tra

| Ảnh | Loại bằng chứng |
| --- | --- |
| [Lịch ADMIN](docs-screenshots/sprint2/dev-calendar-admin-real.png) | Phòng/block API thật, có thông báo chờ booking |
| [Chi tiết block](docs-screenshots/sprint2/dev-calendar-block-detail-real.png) | Lý do/giờ từ API thật |
| [Lịch mobile đã cuộn](docs-screenshots/sprint2/dev-calendar-admin-mobile-scrolled-real.png) | API thật, 375px, cột phòng cố định |
| [Giới hạn STAFF](docs-screenshots/sprint2/dev-calendar-staff-limited-real.png) | Chưa có quyền đọc danh mục; không giả phòng |
| [Tra cứu STAFF](docs-screenshots/sprint2/dev-bookings-staff-real.png) | Booking API thật |
| [Tra cứu mobile](docs-screenshots/sprint2/dev-bookings-staff-mobile-real.png) | API thật, 375px, dữ liệu thiếu hiển thị dấu — |
| [Mã không tồn tại](docs-screenshots/sprint2/dev-bookings-staff-not-found-real.png) | 404 thật |
| [Lịch dữ liệu mẫu](docs-screenshots/sprint2/dev-calendar-staff-du-lieu-mau.png) | Fixture, có banner |
| [Tra cứu dữ liệu mẫu](docs-screenshots/sprint2/dev-bookings-staff-du-lieu-mau.png) | Fixture, có banner |
| [Lỗi một phòng](docs-screenshots/sprint2/dev-calendar-partial-error-simulated.png) | HTTP 500 giả lập, không gắn nhãn API thật |

## 7. Phụ thuộc và DTO đề xuất — CHƯA có API

**Phước Sang + Bảo Long:** mở quyền đọc phòng/loại phòng cho STAFF hoặc thêm endpoint chỉ đọc dành cho staff. Hiện block có STAFF nhưng danh mục chỉ ADMIN. Hoàn thiện kiểm tra block chồng booking CONFIRMED/hold còn hạn và đồng bộ khóa với tạo booking; frontend không sửa backend hoặc thử tạo block đè booking để tác động dữ liệu.

**Phước Sang phối hợp Thanh Binh:** `GET /api/v1/staff/room-calendar?from=&to=&roomTypeId=` cho STAFF/ADMIN. Đề xuất `{from,to,rooms:[{physicalRoomId,roomNumber,floorNumber,roomTypeId,roomTypeName,isActive,entries:[...]}]}`. Entry BLOCK gồm `id,startAt,endAt,reason,createdByName?`; BOOKING gồm `id,startAt,endAt,bookingCode,status,holdExpiresAt?`. Nhận ISO offset, `[startAt,endAt)`, không chứa PII khách. Đây là DTO đề xuất, chưa được backend chấp thuận/triển khai.

**Thanh Binh:** `GET /api/v1/staff/bookings/search?phone=` cho STAFF/ADMIN, từ chối tìm trống và không trả toàn bộ danh sách. Bổ sung DTO staff với `roomTypeName`, `roomNumber` nullable, `customer.phoneMasked/emailMasked`; chưa cần fullName chưa che. Chốt `id` hay `bookingId` và tiền JSON number hay decimal string (fixture đề xuất dùng decimal string; API mã thật hiện dùng number). Đưa 5 commit ngoài dev (`45e0642`, `317adb7`, `47dcad1`, `5dff18c`, `46a1694`) qua review/tích hợp, giữ sửa RBAC trên dev.

## 8. Trạng thái kế hoạch

- **M5-04: Bị chặn** ở mức toàn task — chưa có lịch booking tổng hợp và STAFF chưa được đọc danh sách phòng. Phần frontend ADMIN/block + fixture: chờ review.
- **M5-03: Bị chặn** ở mức toàn task — tra cứu mã thật đạt nhưng SĐT/metadata/liên hệ chưa có. Phần tra cứu mã frontend: chờ review.
- Không ghi Hoàn thành chỉ vì CI xanh hoặc fixture đạt. Chưa sửa file Excel.
