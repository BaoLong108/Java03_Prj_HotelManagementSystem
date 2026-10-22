# Bàn giao M5-04, M5-03 — Sprint 2

Ngày kiểm tra: **22/10/2026**. Nhánh: **DatHVT-S2**, bắt đầu từ `DatHVT@c8218a6`.

**Frontend đã triển khai; chưa đủ điều kiện đóng toàn bộ hai task vì thiếu API backend.** M5-04 chỉ đạt với fixture. M5-03 tra cứu theo mã đã kiểm chứng API thật; tìm theo SĐT và metadata phòng/liên hệ đã che còn chờ Thanh Binh. Chưa mở PR, không xử lý merge PR #3 hoặc rebase main.

Chỉ thay đổi `/frontend`; không sửa hoặc merge code backend, migration, docs chung. Không thêm dependency hoặc sửa package.json/package-lock.json. M5-04 được commit và kiểm thử trước khi bắt đầu M5-03.

## 1. Khảo sát backend trước khi viết code

Đã `git fetch --all`, liệt kê remote/log và đọc code trên mọi nhánh remote. Các đầu nhánh tại thời điểm khảo sát:

| Nhánh | Commit |
| --- | --- |
| origin/main | 3676923 |
| origin/DatHVT | c8218a6 |
| origin/BaoLong | 2fca3e4 |
| origin/PhanThanhBinh | 47dcad1 |
| origin/PhuocSang | 44174a6 |
| origin/dev | b6bbe14 |
| origin/fix/sprint1-integration-stabilization | cd8bb39 |
| origin/NguyenDinhDat | 19ca103 |

Đã đọc `docs/api-contract.md` (bản cập nhật c209c27), `docs/database.md`, `docs/architecture.md` (d03cd7f), cùng `docs/sprint2-long-integration-contracts.md` và checklist tại `origin/BaoLong@2fca3e4` bằng `git show`. Không chép các tài liệu đó sang nhánh frontend.

| Chức năng | Endpoint / nguồn | Quyền / DTO / quyết định frontend |
| --- | --- | --- |
| RoomBlock M2-06 đã có | `GET /api/v1/admin/room-blocks?roomId=&history=false`; controller d889bfc trên nhánh PhuocSang, cũng có trong BaoLong 2fca3e4 | `id, physicalRoomId, roomNumber, startAt, endAt, reason, createdByUserId, createdAt, active, releasedByUserId, releasedAt`; BaoLong cho STAFF/ADMIN đọc. Chỉ là block, chưa phải lịch tổng hợp. |
| Danh mục phòng | `GET /api/v1/admin/rooms/physical`, `/types` | ADMIN; không dùng các route này để lắp lịch STAFF hoặc tự suy luận phòng trống. |
| Lịch phòng tổng hợp | Chưa tìm thấy trên mọi remote | **DTO ĐỀ XUẤT** `GET /api/v1/staff/room-calendar?from=&to=&roomTypeId=`; chờ Phước Sang M2-06 phối hợp Thanh Binh. |
| Tra cứu mã booking M3-03 | **API THẬT** `GET /api/v1/bookings/{bookingCode}`; controller 4d64567 (PhanThanhBinh), có ở BaoLong 2fca3e4 | CUSTOMER xem của mình; STAFF/ADMIN đọc theo mã trong security/service của BaoLong. Frontend dùng endpoint này ngay. |
| Liệt kê booking | `GET /api/v1/bookings` | Danh sách của CUSTOMER. Không dùng cho staff, không có tìm trống/liệt kê tất cả. |
| Tìm theo SĐT | Chưa có trên mọi remote | **DTO ĐỀ XUẤT** `GET /api/v1/staff/bookings/search?phone=`; chờ Thanh Binh. |

### Chênh lệch DTO thật và đề xuất

`BookingResponse` thật: `id`, `bookingCode`, `customerId`, `roomTypeId`, `guestCount`, `startAt`, `endAt`, `holdExpiresAt`, `status`, `baseAmount`, `discountRate`, `discountAmount`, `payableAmount` và các mốc tạo/xác nhận/hoàn tất/hủy/hết hạn.

- Tiền thật là **JSON number** từ Java BigDecimal; adapter nhận number, chuyển thành chuỗi để định dạng, không tính lại giá. DTO tìm SĐT đề xuất nhận **decimal string** như yêu cầu.
- DTO thật không có `roomTypeName`, `roomNumber`, `customer.phoneMasked/emailMasked`. UI hiển thị “Máy chủ chưa cung cấp”, không dựng thông tin hoặc gọi API admin để bù.
- Bỏ `customerId` khỏi view model. DTO đề xuất có `fullName`, nhưng frontend không hiển thị vì đó chưa phải tên đã che. Chỉ hiển thị `phoneMasked/emailMasked` server trả. Nếu nhận liên hệ không che, ẩn kết quả và báo lỗi tích hợp.
- Trạng thái và giá là snapshot server. Không tự đổi PENDING_PAYMENT thành EXPIRED theo đồng hồ, không quyết định availability, giá hoặc quyền.
- Thời gian API lịch gửi ISO `+07:00`; nhận cả `Z`/offset từ backend và hiển thị `dd/MM/yyyy HH:mm`, `Asia/Ho_Chi_Minh`.

## 2. Tiêu chí và trạng thái

“ĐẠT VỚI FIXTURE” dưới đây nghĩa là **chưa kiểm chứng API thật cho luồng nghiệp vụ đó**.

| Task / tiêu chí | Kết quả | File chính |
| --- | --- | --- |
| M5-04 lưới 7 ngày, hàng phòng vật lý, cột phòng cố định, tuần trước/sau/hôm nay, ngày bắt đầu, lọc loại | ĐẠT VỚI FIXTURE | `src/features/staff/calendar/RoomCalendarPage.tsx`, `RoomCalendarGrid.tsx` |
| Booking/block theo giờ, nhãn màu + chữ, chi tiết, hold expiry, lý do block, phòng Ngừng bán | ĐẠT VỚI FIXTURE | `RoomCalendarGrid.tsx`, `roomCalendar.css`, `roomCalendar.fixture.ts` |
| Hàm vị trí [start,end), cắt phạm vi, qua ngày, tiếp giáp và khoảng cách 2 giờ, ẩn CANCELLED/EXPIRED | ĐẠT — unit test; lưới trực quan ĐẠT VỚI FIXTURE | `calendarGeometry.ts`, `tests/calendar.test.mjs` |
| Loading, lỗi message server, rooms rỗng | ĐẠT — kiểm thử phản hồi HTTP giả lập; chưa có API lịch nghiệp vụ | `RoomCalendarPage.tsx` |
| Lịch với backend thật | CHƯA ĐẠT — chưa có endpoint, STAFF/ADMIN nhận 403 | `roomCalendarApi.ts` |
| M5-03 tìm mã booking và 404 không tìm thấy | ĐẠT — API thật + Edge, STAFF/ADMIN | `bookings/bookingSearchApi.ts`, `BookingSearchPage.tsx` |
| Tìm SĐT, đủ 5 trạng thái, metadata và liên hệ đã che | ĐẠT VỚI FIXTURE; API thật CHƯA ĐẠT | `bookingSearch.fixture.ts`, `bookingSearchApi.ts` |
| Validate, không tìm trống/liệt kê tất cả, không gửi trùng, bỏ phản hồi cũ | ĐẠT — unit + Edge (pending/race dùng HTTP giả lập) | `BookingSearchPage.tsx`, `bookingSearchFormat.ts` |
| Bảo vệ PII, decimal string, không tính lại giá, deep link sang tuần booking | ĐẠT — unit; deep link + đủ metadata ĐẠT VỚI FIXTURE | `bookingSearchApi.ts`, `bookingSearchFormat.ts` |
| STAFF/ADMIN truy cập, CUSTOMER → 403 | ĐẠT — auth thật, cả hai route | `src/App.tsx`, RequireAuth có sẵn |
| HTTP 403 → /403, HTTP 401 → /login | ĐẠT — 403 backend thật, 401 giả lập trên Edge và unit client | Hai page, shared API/auth có sẵn |
| Fixture mặc định tắt, banner bắt buộc khi bật, không tự fallback | ĐẠT — unit + Edge hai chế độ | `.env.example`, `shared/fixtureMode.tsx`, hai API adapter |
| 375×812 không tràn ngang; lưới cuộn riêng | ĐẠT — `document.documentElement.scrollWidth === 375`; lịch dùng fixture, tra cứu mã dùng API thật | Hai file CSS; ảnh bên dưới |
| Chỉ đọc, không hủy/sửa/check-in/check-out | ĐẠT | Hai màn hình staff |

## 3. Kiểm thử API thật

### Môi trường

- Docker: `java03_prj_hotelmanagementsystem-postgres-1`, PostgreSQL repo **5433**, healthy. Giữ nguyên `viral-product-radar-postgres` trên **5432**.
- Chạy bản snapshot **không chỉnh sửa** của `origin/BaoLong@2fca3e4` trong `%TEMP%/hotel-pms-s2-e2e/runtime-2fca3e4/backend`, lấy bằng `git archive`. Nhánh frontend không được merge backend này.
- DB kiểm thử riêng `hotel_pms_s2_20261022`; `DB_URL=jdbc:postgresql://localhost:5433/hotel_pms_s2_20261022`. Không thay đổi DB hiện hữu của Sprint 1.
- Profile `dev`; `/actuator/health` trả `UP`. Profile này bật pricing stub ports theo cấu hình backend: đây không phải kiểm chứng đầy đủ tích hợp pricing.
- Frontend fixture ở 5173, API thật ở 5174. Biến môi trường backend `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5174`; không sửa file CORS. Lần đầu 5174 bị CORS chặn login, đã cấu hình lại môi trường rồi chạy lại toàn bộ 8 nhóm Edge thành công.
- Seed dev: `staff@hotel-pms.test` / STAFF; `admin@hotel-pms.test` / ADMIN; `customer@hotel-pms.test` / CUSTOMER. Không lưu token/mật khẩu vào bàn giao hoặc ảnh.
- Dữ liệu test tạo qua API trong DB riêng: roomTypeId 6, physicalRoomId 16, booking `BK261022-510EFE`. Không chỉnh SQL/migration để tạo kết quả mong muốn.

| Ca test | Mong đợi | Thực tế | Kết quả |
| --- | --- | --- | --- |
| Login 3 seed dev | 200 + token | 200, token chỉ dùng trong bộ nhớ | ĐẠT |
| Tạo loại phòng ADMIN | 201 | 201, id 6 | ĐẠT — chuẩn bị dữ liệu |
| Tạo phòng vật lý ADMIN | 201 | 201, id 16 | ĐẠT — chuẩn bị dữ liệu |
| Tạo booking CUSTOMER | 201 | 201, PENDING_PAYMENT | ĐẠT — chuẩn bị dữ liệu |
| STAFF GET booking theo mã | 200 | 200, DTO number/Instant đúng adapter | ĐẠT |
| ADMIN GET booking theo mã | 200 | 200, cùng booking | ĐẠT |
| STAFF/ADMIN mã không tồn tại (2 ca) | 404 BOOKING_NOT_FOUND | Đúng 404/code/message/details | ĐẠT |
| Token sai GET booking | 401 | 401 UNAUTHORIZED | ĐẠT |
| STAFF/ADMIN GET lịch đề xuất (2 ca) | 200 calendar | 403 FORBIDDEN; không có endpoint trong source | CHƯA ĐẠT |
| STAFF/ADMIN tìm SĐT đề xuất (2 ca) | 200 items | 403 FORBIDDEN; không có endpoint trong source | CHƯA ĐẠT |

Request/response rút gọn (token đã che):

```http
POST /api/v1/bookings
Authorization: Bearer <CUSTOMER_TOKEN_REDACTED>
Content-Type: application/json

{"roomTypeId":6,"guestCount":2,"startAt":"2026-10-23T12:00:00+07:00","endAt":"2026-10-24T10:00:00+07:00"}
HTTP 201
{"id":1,"bookingCode":"BK261022-510EFE","status":"PENDING_PAYMENT",...}

GET /api/v1/bookings/BK261022-510EFE
Authorization: Bearer <STAFF_OR_ADMIN_TOKEN_REDACTED>
HTTP 200
{"id":1,"bookingCode":"BK261022-510EFE","roomTypeId":6,"guestCount":2,"startAt":"2026-10-23T05:00:00Z","endAt":"2026-10-24T03:00:00Z","holdExpiresAt":"2026-10-22T02:47:44.569503Z","status":"PENDING_PAYMENT","baseAmount":1200000.00,"discountRate":0.00,"payableAmount":1200000.00,...}

GET /api/v1/bookings/S2-NOT-FOUND
Authorization: Bearer <STAFF_OR_ADMIN_TOKEN_REDACTED>
HTTP 404
{"code":"BOOKING_NOT_FOUND","message":"Không tìm thấy đơn đặt phòng","details":{}}

GET /api/v1/bookings/BK261022-510EFE
Authorization: Bearer <INVALID>
HTTP 401
{"code":"UNAUTHORIZED","message":"Cần đăng nhập hoặc token không hợp lệ","details":{}}

GET /api/v1/staff/room-calendar?from=2026-10-08T00%3A00%3A00%2B07%3A00&to=2026-10-15T00%3A00%3A00%2B07%3A00
Authorization: Bearer <STAFF_OR_ADMIN_TOKEN_REDACTED>
HTTP 403
{"code":"FORBIDDEN","message":"Bạn không có quyền truy cập","details":{}}

GET /api/v1/staff/bookings/search?phone=0900000123
Authorization: Bearer <STAFF_OR_ADMIN_TOKEN_REDACTED>
HTTP 403
{"code":"FORBIDDEN","message":"Bạn không có quyền truy cập","details":{}}
```

Không phát hiện API thật trả liên hệ khách chưa che (DTO thật chưa có thông tin liên hệ). Không tự sửa các thiếu sót backend này. Cần backend bổ sung/chốt endpoint, DTO và quyền STAFF/ADMIN trước nghiệm thu toàn bộ.

## 4. Kiểm thử frontend và ảnh

### Tự động

- `npm ci`: PASS ở bước chuẩn bị. Lần cài sạch cuối khi Vite còn mở gặp EPERM; sau khi dừng Vite, bước audit registry bị treo. Đã chạy **`npm ci --offline --no-audit`: PASS, 27 packages**, đúng lockfile/cache, rồi chạy test/build phía dưới. Không dùng lần lỗi/treo làm PASS và không tuyên bố đã audit bảo mật lại ở lần cuối.
- `npm test`: **44/44 PASS** (24 kiểm thử Sprint 1, 11 lịch/time/API, 9 tra cứu/PII/tiền/API).
- `npm run build`: **PASS**, TypeScript và Vite production bundle.
- Playwright **1.58.2 + Edge**, dependency/script tạm nằm ngoài repo; không thêm package hoặc lockfile.
- **11/11 nhóm assertion Edge PASS**: 3 nhóm lịch trước M5-03, 8 nhóm tra cứu/tích hợp/lỗi sau M5-03. PASS của kiểm tra xử lý 403 chỉ chứng minh frontend phản ứng đúng, không biến endpoint chưa có thành nghiệp vụ ĐẠT.

| Nhóm Edge | Loại dữ liệu | Kết quả |
| --- | --- | --- |
| Lịch STAFF: 6 phòng, 5 thanh, 2h cách nhau 12px, chi tiết block, lọc, đổi tuần, sticky và 375px | Auth thật, lịch fixture | ĐẠT VỚI FIXTURE |
| Lịch ADMIN | Auth thật, lịch fixture | ĐẠT VỚI FIXTURE |
| CUSTOMER vào lịch | Auth thật | ĐẠT → /403 |
| Booking STAFF: validate, tìm mã/SĐT, 6 kết quả/5 status, che dữ liệu, rỗng, deep link, 375px/menu | Auth thật, booking fixture | ĐẠT VỚI FIXTURE |
| Booking ADMIN | Auth thật, booking fixture | ĐẠT VỚI FIXTURE |
| CUSTOMER vào tra cứu | Auth thật | ĐẠT → /403 |
| Tra cứu mã STAFF, 404, số tiền, giờ Việt Nam, 375px; tìm SĐT bị 403 | Backend thật | Luồng mã ĐẠT, SĐT CHƯA ĐẠT |
| Tra cứu mã ADMIN, 404; tìm SĐT bị 403 | Backend thật | Luồng mã ĐẠT, SĐT CHƯA ĐẠT |
| API lịch chưa có → /403, không fallback fixture | Backend thật | Xử lý lỗi ĐẠT; nghiệp vụ lịch CHƯA ĐẠT |
| Pending, chống submit trùng, bỏ phản hồi cũ, dữ liệu masked/unmasked, 401 | HTTP giả lập có kiểm soát, auth thật | ĐẠT cho hành vi frontend; không phải nghiệm thu API |
| Loading lịch, lỗi server, rooms rỗng | HTTP giả lập có kiểm soát, auth thật | ĐẠT cho hành vi frontend; không phải nghiệm thu API |

Script và log cục bộ: `%TEMP%/hotel-pms-s2-e2e/{real-api.ps1,api-results.json,calendar-ui.mjs,calendar-ui-results.json,booking-ui.mjs,booking-ui-results.json}`. Token không xuất ra log báo cáo. Bộ script không thuộc PR, unit tests nằm trong repo.

### Ảnh đã kiểm tra trực quan

| Ảnh | Nội dung |
| --- | --- |
| [calendar-desktop-fixture.png](docs-screenshots/sprint2/calendar-desktop-fixture.png) | Lịch 7 ngày, fixture có banner |
| [calendar-block-detail-fixture.png](docs-screenshots/sprint2/calendar-block-detail-fixture.png) | Chi tiết lý do block |
| [calendar-mobile-fixture.png](docs-screenshots/sprint2/calendar-mobile-fixture.png) | Lịch 375px, khung cuộn riêng |
| [calendar-mobile-scrolled-fixture.png](docs-screenshots/sprint2/calendar-mobile-scrolled-fixture.png) | Đã cuộn ngang, cột phòng cố định |
| [bookings-desktop-fixture.png](docs-screenshots/sprint2/bookings-desktop-fixture.png) | 6 kết quả SĐT mẫu, đủ 5 trạng thái |
| [bookings-mobile-fixture.png](docs-screenshots/sprint2/bookings-mobile-fixture.png) | Pending và liên hệ đã che ở 375px |
| [bookings-desktop-real.png](docs-screenshots/sprint2/bookings-desktop-real.png) | Tra cứu mã từ backend thật |
| [bookings-mobile-real.png](docs-screenshots/sprint2/bookings-mobile-real.png) | Backend thật ở 375px; hiển thị rõ metadata thiếu |
| [staff-menu-mobile.png](docs-screenshots/sprint2/staff-menu-mobile.png) | Menu mở, 375px |
| [calendar-api-unavailable.png](docs-screenshots/sprint2/calendar-api-unavailable.png) | API lịch thật chưa có, chuyển trang 403 |

## 5. Cách chạy lại

Backend cần cung cấp các endpoint ở `origin/BaoLong@2fca3e4` hoặc bản tích hợp tương đương; backend đang nằm trên DatHVT-S2 chưa chứa các thay đổi đó. Không merge backend chỉ để thử frontend. Dùng checkout/snapshot riêng của backend; PostgreSQL repo 5433; không dừng DB khác ở 5432. Chờ `/actuator/health` UP rồi chạy:

```powershell
cd frontend
npm ci
npm test
npm run build
$env:VITE_USE_FIXTURES='false'
npm run dev -- --host localhost --port 5173 --strictPort
```

Đăng nhập seed STAFF/ADMIN. Tại `/staff/bookings`, tra cứu mã đã tồn tại trong DB; mã `BK261022-510EFE` chỉ có trong DB kiểm thử nêu trên. CUSTOMER không được vào cả hai trang staff.

Để xem phần đang chờ API: dừng Vite, đặt `$env:VITE_USE_FIXTURES='true'` rồi khởi động lại. Auth vẫn dùng backend thật. Trên lịch bấm **Xem tuần mẫu 08/10/2026**; trên tra cứu dùng `BK-MAU-001`, `BK-MAU-003` hoặc SĐT mẫu `0900000123`. Banner mẫu phải luôn hiện. Sau thử nghiệm, dừng Vite và bỏ biến bằng `Remove-Item Env:VITE_USE_FIXTURES` để trở về mặc định tắt.

Kiểm tra ở 375×812: trang không tràn ngang; cuộn chỉ trong lịch; cột tên phòng không di chuyển. Trên tra cứu kiểm tra chưa nhập, không tìm thấy, 5 status, pending expiry và liên kết xem tuần lịch.

## 6. Bảng file (so với DatHVT)

| Đường dẫn dưới frontend/ | Có sẵn / mới | Thay đổi |
| --- | --- | --- |
| `.env.example` | Có sẵn | Flag fixture mặc định false |
| `src/App.tsx` | Có sẵn | Hai trang staff thật trong guard hiện hữu |
| `src/features/staff/StaffLayout.tsx` | Có sẵn | Nhãn Tra cứu booking |
| `src/features/staff/WorkspacePages.tsx` | Có sẵn | Bỏ placeholder staff đã thay thế, giữ trang admin |
| `src/shared/layout/AppLayout.tsx` | Có sẵn | Tiêu đề/breadcrumb tra cứu |
| `src/features/staff/shared/staffTime.ts` | Mới | Ngày/giờ hotel +07, định dạng và range |
| `src/features/staff/shared/bookingStatus.ts` | Mới | Enum/nhãn trạng thái chung |
| `src/features/staff/shared/fixtureMode.tsx` | Mới | Flag và banner rõ ràng |
| `src/features/staff/calendar/roomCalendarApi.ts` | Mới | DTO đề xuất, parse whitelist, shared client, không fallback |
| `src/features/staff/calendar/roomCalendarTypes.ts` | Mới | Kiểu rooms/booking/block/query |
| `src/features/staff/calendar/roomCalendar.fixture.ts` | Mới | 2 loại, 6 phòng, booking/block/phòng ngừng bán |
| `src/features/staff/calendar/calendarGeometry.ts` | Mới | Hàm thuần clip/position/overlap/lanes |
| `src/features/staff/calendar/RoomCalendarGrid.tsx` | Mới | Lưới giờ, cột sticky, thanh và chi tiết |
| `src/features/staff/calendar/RoomCalendarPage.tsx` | Mới | Bộ lọc, trạng thái fetch, chi tiết, lỗi, điều hướng |
| `src/features/staff/calendar/roomCalendar.css` | Mới | Lưới cuộn riêng, thanh, controls, mobile |
| `src/features/staff/bookings/bookingSearchApi.ts` | Mới | Adapter API mã thật / SĐT đề xuất, bảo vệ PII |
| `src/features/staff/bookings/bookingSearchTypes.ts` | Mới | Query đúng một tham số và view model |
| `src/features/staff/bookings/bookingSearchFormat.ts` | Mới | Validate, tiền snapshot chính xác, calendar deep link |
| `src/features/staff/bookings/bookingSearch.fixture.ts` | Mới | Tra cứu mẫu đồng nhất dữ liệu lịch |
| `src/features/staff/bookings/BookingSearchPage.tsx` | Mới | Form, thẻ kết quả, loading/rỗng/lỗi, abort và chống submit trùng |
| `src/features/staff/bookings/bookingSearch.css` | Mới | Thẻ, status, form responsive |
| `tests/calendar.entry.ts`, `tests/calendar.test.mjs` | Mới | 11 kiểm thử lịch/thời gian/contract |
| `tests/bookingSearch.entry.ts`, `tests/bookingSearch.test.mjs` | Mới | 9 kiểm thử tra cứu/PII/tiền/contract |
| `tests/routes.test.mjs` | Có sẵn | Cập nhật assertion trang mới, giữ guard |
| `docs-screenshots/sprint2/*.png` | Mới | 10 ảnh bằng chứng |
| `M5-03-M5-04-handover.md` | Mới | Bàn giao, kết quả và phụ thuộc backend |
| `M5-03-M5-04-pr-draft.md` | Mới | Nội dung PR để mở sau PR #3 |

## 7. Tin nhắn soạn sẵn — chưa gửi

**Gửi Phước Sang:** Sang ơi, M5-04 frontend đã xong lịch/fixture và test 375px. Mình đã đọc RoomBlock trên `44174a6` và bản tích hợp `2fca3e4`, nhưng chưa thấy API lịch gộp booking + block. Nhờ bạn chốt `GET /api/v1/staff/room-calendar?from=&to=&roomTypeId=` cho STAFF/ADMIN: rooms theo physicalRoomId, roomNumber, floorNumber, roomTypeId/name, isActive; entries BOOKING/BLOCK gồm id, startAt/endAt, bookingCode/status/holdExpiresAt hoặc reason/createdByName. Dùng khoảng [start,end), nhận +07:00, không có PII khách. Hiện gọi thật nhận 403; cần endpoint rồi mình chạy lại nghiệm thu.

**Gửi Thanh Binh:** Binh ơi, M5-03 đã nối thật `GET /api/v1/bookings/{bookingCode}` ở `4d64567`/`BaoLong@2fca3e4`, test STAFF/ADMIN 200 và mã sai 404 đạt. DTO hiện dùng id, tiền JSON number và chưa có tên loại/số phòng/liên hệ đã che. Nhờ bạn chốt API tìm SĐT `GET /api/v1/staff/bookings/search?phone=` (đúng một điều kiện, không trả danh sách khi trống), bổ sung roomTypeName, roomNumber nullable và phoneMasked/emailMasked cho kết quả staff; thống nhất tiền number hay decimal string. Frontend hiện không hiện tên/phone/email chưa che. Endpoint SĐT đang trả 403, cần API thật để nghiệm thu phần còn lại.

## 8. Kết luận và việc còn lại

- **M5-04:** chưa chuyển Hoàn thành; phần frontend đạt với fixture, chờ **Phước Sang M2-06** chốt và triển khai lịch tổng hợp (phối hợp dữ liệu booking với Thanh Binh), sau đó chạy lại API/Edge thật.
- **M5-03:** chưa chuyển Hoàn thành toàn task; tra cứu mã thật đạt, chờ **Thanh Binh M3-03** tìm SĐT và metadata phòng/liên hệ đã che, sau đó chạy lại đủ ca thật.
- Các tiến trình backend/frontend do tác vụ tạo đã dừng. Hai container PostgreSQL vẫn được giữ nguyên. DB test riêng được giữ để có thể tái hiện kết quả; không xóa dữ liệu.
- Nội dung PR chuẩn bị ở [M5-03-M5-04-pr-draft.md](M5-03-M5-04-pr-draft.md). Chưa tạo PR, chưa rebase main; chờ người dùng xử lý PR #3 theo yêu cầu.
