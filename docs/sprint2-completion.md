# Sprint 2 — Bản tích hợp hoàn tất ngày 26/10/2026

Sprint 2 theo kế hoạch là 19–25/10/2026. Long hoàn thiện phần còn thiếu ngày 26/10 theo yêu cầu của nhóm. Đây là trạng thái kỹ thuật sau tích hợp, không phải xác nhận các owner đã tự hoàn thành trên nhánh cá nhân.

## Nguồn tích hợp

Baseline `origin/dev@1789332` đã chứa PR #2 Security/Loyalty, PR #3 Auth/UI và PR #5 fixture thời gian Booking. Fetch ngày 26/10 không có commit mới hơn các mốc dưới đây:

| Owner | Remote head | Phần đã có và cách tái sử dụng |
| --- | --- | --- |
| Thanh Bình | `PhanThanhBinh@4115267` (25/10) | Merge nguyên nhánh: Booking UI, hold, job, tests và dev mới nhất |
| Phước Sang | `PhuocSang@44174a6` (16/10) | RoomBlock đã có trên dev; Long bổ sung khóa chung, overlap và availability tests |
| Đình Đạt | `NguyenDinhDat@94fc7eb` (23/10) | Cherry-pick `056b5e0`, `94fc7eb`: Payment entity/repository và PriceSnapshot; Long nối service/controller/Booking/PDF |
| Thông Đạt | `DatHVT-S2@c3dbba5` (23/10), PR #4 | Merge nguyên head PR: Calendar/Lookup/components; Long cung cấp API thật và cập nhật adapters |

Không sửa nhánh cá nhân, main, schema V1/V2 hoặc các file cá nhân chưa commit. Phần tích hợp thực hiện trên `codex/sprint2-completion`, đưa vào dev qua PR sau khi CI đạt.

## Đối chiếu WBS

Nguồn: `outputs/01a11bd1-09bd-7d42-a507-ef5e453c6794/Ke_hoach_PMS_4_sprint_theo_tuan.xlsx`, sheet `Sprint 2`, hàng 11–19. Giữ nguyên file kế hoạch gốc.

| Task | Owner kế hoạch | Trạng thái kỹ thuật | Bằng chứng |
| --- | --- | --- | --- |
| M2-06 (P0) | Sang | DONE | JWT actor, block/release; từ chối live hold/confirmed; khóa cùng physical room với Booking |
| M2-05 (P1) | Sang | DONE | Search loại phòng theo availability thật; block chỉ tác động đúng khoảng; release không sửa booking |
| M3-03 (P0) | Bình | DONE | Một transaction chọn/khóa physical room và tạo hold 15 phút, snapshot giá từ Quote |
| M3-04 (P0) | Bình | DONE | Job EXPIRED và COMPLETED; khóa/reload từng booking, kiểm tra paid/endAt, lặp an toàn |
| M3-07 (P0) | Bình | DONE | PostgreSQL integration tests tranh một phòng, block-vs-hold, hold hết hạn, hai kỳ ở kề nhau |
| M4-03 (P0) | Đình Đạt | DONE | Demo payment thành công/thất bại, owner, amount DB, idempotency và payment-vs-expiry race |
| M4-06 (P1) | Đình Đạt | DONE | PDF thật chứa mã booking, khoảng ở, snapshot, giao dịch và số đã trả; owner/Staff/Admin |
| M5-04 (P0) | Thông Đạt | DONE | Staff/Admin Calendar dùng API tổng hợp booking + block trên phòng vật lý; filter tuần/loại phòng |
| M5-03 (P1) | Thông Đạt | DONE | Tra cứu mã booking hoặc SĐT đầy đủ, trạng thái/giá/số phòng thật, contact được che |

Long giữ Security/RBAC và Loyalty contract đã merge. Quote đọc `room_types` và `customer_profiles.tier_code` thật, STANDARD 0%, VIP 10%, DIAMOND 15%. Cộng chi tiêu/điểm và tự nâng hạng vẫn thuộc Sprint 3.

## Contract đang chạy

Prefix `/api/v1`. Thời gian ISO 8601 có offset; khi gửi `+07:00` trong URL phải encode dấu `+`. Backend chuẩn hóa số đêm theo ngày ở `Asia/Ho_Chi_Minh`. Khoảng overlap là `[startAt,endAt)`. FE mặc định nhận 12:00, trả 10:00.

| Method/path | Role | Request | Response |
| --- | --- | --- | --- |
| GET `/rooms` | Public | Không | Catalog loại phòng, không dùng số phòng active làm availability |
| GET `/rooms/availability` | Public | `startAt`, `endAt`, `guestCount` | Mảng loại phòng còn trống: `id,code,name,description,maxOccupancy,basePricePerNight,availableRoomCount` |
| GET `/pricing/quote` | Public | `roomTypeId,startAt,endAt,guestCount` | QuoteResponse, bao gồm `nights,appliedTier` và bốn trường snapshot tiền |
| POST `/bookings` | CUSTOMER | `{roomTypeId,startAt,endAt,guestCount}` | 201 BookingResponse: `id,bookingCode,holdExpiresAt,status` và snapshot; không lộ physicalRoomId |
| GET `/bookings` | CUSTOMER | Không | Booking của chính mình |
| GET `/bookings/{bookingCode}` | Owner / STAFF / ADMIN | Mã booking | BookingResponse hiện có |
| POST `/payments/demo` | CUSTOMER owner | `{bookingCode,idempotencyKey,simulateFailure?}` | PaymentResponse dưới đây |
| GET `/bookings/{bookingCode}/confirmation.pdf` | Owner / STAFF / ADMIN | Mã booking đã trả tiền | `application/pdf`, attachment, no-store; chưa trả tiền trả 409 |
| GET `/staff/room-calendar` | STAFF / ADMIN | `from,to,roomTypeId?`; tối đa 31 ngày | `{from,to,rooms,roomTypes}` |
| GET `/staff/bookings/search` | STAFF / ADMIN | Chính xác một trong `bookingCode` hoặc `phone` | `{items:[...]}`, tối đa 50, không có kết quả trả 200 + mảng rỗng |
| POST `/admin/room-blocks` | STAFF / ADMIN | `{physicalRoomId,startAt,endAt,reason}` | BlockResponse, actor lấy từ JWT |
| DELETE `/admin/room-blocks/{id}` | STAFF / ADMIN | Block ID | Release đúng block, giữ audit actor |

PaymentResponse: `{paymentAttemptId,bookingId,bookingCode,provider,paymentStatus,bookingStatus,requestedAmount,succeededAt}`. Provider ở đây luôn `DEMO`. Idempotency key không rỗng, tối đa 100 ký tự, phạm vi `(bookingId,provider,key)`. Cùng key trả attempt cũ, kể cả FAILED. Retry sau FAILED cần key mới; khác key sau SUCCEEDED trả 409 `BOOKING_ALREADY_PAID`. Một booking tối đa một SUCCEEDED, được bảo vệ bằng khóa booking và unique constraint V1.

Payment không nhận số tiền, user ID hoặc trạng thái thành công từ client. Số tiền lấy từ snapshot booking. FE chỉ hiển thị thành công sau POST payment và đọc lại Booking; không tự gọi confirm để giả trả tiền. Endpoint confirm cũ giữ tương thích nhưng yêu cầu payment SUCCEEDED (`PAYMENT_REQUIRED` nếu chưa trả).

Trạng thái: `PENDING_PAYMENT → CONFIRMED` sau payment thành công; `PENDING_PAYMENT → EXPIRED` khi hết hạn; `CONFIRMED → COMPLETED` khi đã trả tiền và `endAt <= now`. Payment FAILED giữ PENDING_PAYMENT đến khi hết hạn. API hủy sẵn có chỉ cho booking chưa trả tiền; không thêm hoàn tiền.

Calendar room: `{physicalRoomId,roomNumber,floorNumber,roomTypeId,roomTypeName,isActive,entries}`. Entry BOOKING: `{id,kind:'BOOKING',bookingCode,status,startAt,endAt,holdExpiresAt}`. Entry BLOCK: `{id,kind:'BLOCK',startAt,endAt,reason}`. Chỉ trả live hold, confirmed/completed và active block đụng khoảng xem. Không chứa contact khách; khoảng trống không đảm bảo phòng còn trống tại lúc tạo hold.

Staff search item: `{bookingId,bookingCode,status,roomTypeName,roomNumber,guestCount,startAt,endAt,holdExpiresAt,baseAmount,discountRate,payableAmount,customer:{phoneMasked,emailMasked}}`. SĐT đầy đủ 7–15 chữ số, có thể có `+` đầu; không cho tìm rỗng hoặc hai bộ lọc cùng lúc. Phone đọc từ `customer_profiles.phone_number`.

Giữ wire contract hiện có của Quote/Booking/Payment: JSON number cho các BigDecimal, tính toán chính xác bằng BigDecimal ở server và NUMERIC tại DB. Staff search mới dùng decimal string cho ba trường tiền/tỷ lệ; FE parser không tính lại tiền. Không thay global Jackson serializer, schema hoặc tên cột.

## Kiểm chứng

- `backend/.\mvnw.cmd -B -ntp verify`: **235 tests, 0 failures, 0 errors, 0 skipped**, package thành công. Local Java 25, compiler release 21; CI chạy Java 21 + PostgreSQL 17.
- `frontend/npm run lint`, `npm test`, `npm run build`: **61 tests PASS**, không skip, lint/build thành công.
- `BookingIntegrationTest` có 32 ca (bao gồm parameterized cases). DB PostgreSQL riêng, không dùng hoặc sửa DB local của nhóm.
- Bao gồm race giữ một phòng, block-vs-hold, payment cùng/khác key, payment-vs-expiry, snapshot bất biến, paid/endAt completion gate, release block không thay booking khác, RBAC/ownership/PII và đọc PDF bằng PDFBox.
- Smoke web thật trên packaged backend dev + PostgreSQL tạm, `VITE_USE_FIXTURES=false`: Customer chọn Suite 10–12/11/2026, 2 khách, Quote 5.000.000 VND → hold → demo payment → CONFIRMED. Staff xem đúng booking trong Calendar và Lookup. Dữ liệu smoke là dữ liệu tạm.
- Chạy CI trên revision của PR tích hợp trước khi merge dev; kết quả và merge SHA nằm trong PR/GitHub Actions. Không dùng các ảnh fixture/ảnh API cũ của PR #4 làm bằng chứng nghiệm thu API mới.

## Chạy và test trong nhóm

Pull dev sau khi PR tích hợp được merge, rồi chạy PostgreSQL persistent, backend profile dev và frontend theo README gốc. Cấu hình mặc định vẫn API `8080`, Vite `5173`; không cần dùng các port smoke riêng.

1. Customer: `/rooms` chọn ngày/số khách, đăng nhập, xem quote/tier server, giữ chỗ, thanh toán demo và tải PDF. `/my-bookings` xem lại hoặc thanh toán hold còn hạn.
2. Staff/Admin: `/staff/calendar` chọn tuần chứa booking; click booking/block để xem chi tiết. `/staff/bookings` tra mã hoặc SĐT đầy đủ, xem contact đã che và tải PDF đã trả tiền.
3. RoomBlock: dùng API có JWT Staff/Admin để chặn/mở; test overlap booking/hold trả 409. UI chặn/mở riêng không nằm trong M5-04/M5-03 của Sprint 2.
4. Dùng Compose/PostgreSQL riêng để giữ dữ liệu sau restart. `DB_EMBEDDED=true` chỉ dành cho demo/test, tạo DB mới mỗi lần chạy. Không bật fixture mode để nghiệm thu API.

Giới hạn đúng phạm vi: PDF dùng nhãn tiếng Anh và ghi rõ chứng từ demo; không phải hóa đơn điện tử. VNPay Sandbox, cộng điểm/nâng hạng và Analytics còn theo Sprint 3/4. Trang chi tiết phòng, quản trị đầy đủ và KPI ngoài những task S2 nêu trên vẫn theo kế hoạch tiếp theo.

## Chốt Booking UI trong PR #6 — 27/10/2026

Availability có loading/empty/error riêng và retry; chỉ phản hồi rỗng thành công mới hiện hết phòng. Key theo ngày/số khách/lượt retry và AbortController loại phản hồi cũ, không cho giữ phòng từ kết quả tìm cũ.

Countdown tái sử dụng `hold.ts`, tính từ `holdExpiresAt` của backend và không tự gán EXPIRED. Booking được đọc lại khi vào hold, mỗi 5 giây, khi chạm hạn, khi quay lại tab hoặc bấm retry. Chỉ response backend đổi trạng thái; lỗi đọc trạng thái chặn thanh toán và cho thử lại. Rời hold hủy request và bỏ kết quả đến muộn.

Thêm 8 test DOM cho component thật bằng jsdom (chỉ devDependency). Lượt kiểm tra cuối: 11/11 tests tập trung (BookingPage + hold), toàn frontend 69/69 PASS, lint/build PASS. Backend không đổi trong lượt này; CI revision `114e847` đã xác nhận 235/235 PASS. Kết quả CI revision cuối xem trực tiếp [PR #6](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/pull/6). PR giữ mở để Long review/duyệt merge; không tự merge dev.
