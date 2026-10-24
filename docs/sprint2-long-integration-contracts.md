# Sprint 2 — Contract Security, Loyalty và tích hợp

Ngày: 19/10/2026. Owner: Nguyễn Ngọc Bảo Long. Nhánh thực hiện: BaoLong, đồng bộ từ origin/dev `cd8bb39`.

Tài liệu phân biệt rõ contract đang có code và đề xuất còn chờ chủ module. Không tạo endpoint, Payment DTO hoặc Pricing Engine giả. [Audit Sprint 1](sprint1-audit-2026-10-19.md) và [checklist cuối sprint](sprint2-acceptance-checklist.md) là hai tài liệu đi kèm.

## 1. Security đang áp dụng trên BaoLong

JWT decoder, issuer, TTL, BCrypt, Auth DTO, CORS, stateless session và DB authority converter được giữ nguyên. Role lấy lại từ DB ở mỗi request; claim role do client tự khai không cấp quyền. Token sai/hết hạn hoặc tài khoản khóa trả 401; tài khoản đã xác thực nhưng thiếu quyền trả 403. Tài khoản không còn role không dùng được API nghiệp vụ.

| Method / endpoint hiện có | Guest | CUSTOMER | STAFF | ADMIN |
| --- | --- | --- | --- | --- |
| GET health, GET rooms / rooms/{id}, GET pricing/quote | Public | Public | Public | Public |
| POST auth/register, auth/login | Public | Public | Public | Public |
| GET auth/me | 401 | Chính mình | Chính mình | Chính mình |
| GET/POST bookings (collection) | 401 | Booking của mình / tạo cho mình | 403 | 403 |
| GET bookings/{bookingCode} | 401 | Chỉ owner | Đọc vận hành | Đọc vận hành |
| POST bookings/{bookingCode}/cancel | 401 | Chỉ owner | 403 | 403 |
| POST bookings/{bookingCode}/confirm | 401 | Chỉ owner, endpoint WIP hiện có | 403 | 403 |
| POST bookings/{bookingCode}/complete | 401 | 403 | Theo service hiện có | Theo service hiện có |
| GET/POST/DELETE admin/room-blocks theo route hiện có | 401 | 403 | Cho phép | Cho phép |
| CRUD admin/rooms/types và admin/rooms/physical | 401 | 403 | 403 | Cho phép |
| Method/path ngoài allowlist | 401 | 403 | 403 | 403 |

- Public không bỏ qua lỗi bearer token đã gửi. Quyền tạo Customer không được nhận từ request register.
- RoomBlock có method security ở controller. `createdByUserId`/`releasedByUserId` luôn lấy từ subject JWT đã xác thực, không lấy từ body/query. Tạo bởi Staff và mở bởi Admin lưu đúng hai actor khác nhau. URL giữ prefix admin để tránh phá contract; quyền thực tế là STAFF/ADMIN.
- Booking confirm kiểm tra owner được lưu trước khi đổi trạng thái. Route Customer cancel luôn dùng owner check, kể cả tài khoản có thêm role STAFF/ADMIN. Quyền đọc vận hành không trở thành quyền sửa booking của người khác.
- Confirm hiện có **chưa xác minh Payment**. Đây chỉ là hardening về danh tính/ownership, không chứng minh booking đã trả tiền. M4-03 phải chốt đường xác nhận server-side với Binh trước khi nghiệm thu thanh toán demo. Long không triển khai payment gate hay thay state machine trong đợt này.
- Complete giữ nguyên service nguồn. Đủ role không thay thế các điều kiện paid/endAt phải nghiệm thu khi M3-04 sẵn sàng. Chưa thêm role PAYMENT hoặc mở callback chưa tồn tại.

## 2. Contract Loyalty đã chuẩn bị

Interface module Loyalty sở hữu: `com.hotelpms.loyalty.MembershipBenefitsProvider`.

```java
MembershipBenefits getMembershipBenefits(BigDecimal eligibleSpendTotal);
```

Bean `LoyaltyService` implement interface này; không đổi API gọi trực tiếp đang có. DTO tái sử dụng: `com.hotelpms.loyalty.dto.MembershipBenefits(tierCode, eligibleSpendTotal, discountRate)`.

| Chi tiêu hợp lệ đã được ghi nhận | tierCode | discountRate |
| --- | --- | --- |
| 0 đến dưới 10.000.000 VND | STANDARD | 0.00 |
| Từ 10.000.000 đến dưới 30.000.000 VND | VIP | 0.10 |
| Từ 30.000.000 VND | DIAMOND | 0.15 |

Input phải do backend đọc từ dữ liệu hợp lệ của khách. Không nhận tier/discount/spending do client tự khai; không cộng booking đang báo giá vào tổng trước khi chọn tier. Guest có thể dùng tổng 0 do server quyết định. Không dùng null để biểu thị Guest: null/âm/quá NUMERIC(15,2)/phần lẻ vượt 2 chữ số trả ApiException 400 `INVALID_LOYALTY_AMOUNT`.

DTO là kết quả chỉ đọc; gọi lại không đổi snapshot đã trả, không ghi DB, không cộng điểm hoặc nâng tier đã lưu. Calculator điểm 1/10k hiện có giữ nguyên; ghi award thuộc M1-06 Sprint 3.

```java
// Inject interface trong adapter của module tiêu thụ; không tạo thêm bean calculator.
MembershipBenefits benefits = benefitsProvider.getMembershipBenefits(recordedEligibleSpendTotal);
LoyaltyTier tier = benefits.tierCode();
BigDecimal rate = benefits.discountRate(); // 0.10, không phải 10.
```

`MembershipBenefitsContractTest` kiểm tra bean qua interface, hai ngưỡng, số tiền giảm minh họa 0/200k/300k cho 2m, validation và snapshot. `LoyaltyServiceTest` giữ nguyên 36 ca.

### Handoff với Pricing khi owner bắt đầu tích hợp

`pricing.port.LoyaltyPricingPort.findTierByUserId(Long)` hiện chỉ trả tier, vẫn được giữ nguyên. Contract mới không tự thay port hoặc cắm bean lookup DB. Long/Đình Đạt cần chốt adapter:

1. Dùng userId từ JWT; Guest được QuoteService xử lý STANDARD trước khi gọi port.
2. Đọc CustomerProfile ở server; STAFF/ADMIN không có profile dùng STANDARD theo contract port hiện có.
3. Chốt nguồn authoritative: `eligible_spend_total` để calculator xác định tier hay `tier_code` đã lưu. Hai dữ liệu phải nhất quán trước khi nối thật; không âm thầm cho discount khác giữa Booking và Quote.
4. Map kết quả provider sang port hiện có, hoặc thống nhất phiên bản port mới trong PR của owner. Trong lần này không đổi Pricing/Room adapter, QuoteService hoặc cách Booking tính giá.

## 3. Khác biệt contract đã phát hiện

| Ranh giới | Source hiện tại | Việc cần thống nhất / owner |
| --- | --- | --- |
| Catalog / availability | `RoomTypeResponse.activeRoomCount` chỉ đếm phòng active, không xét khoảng nghỉ. Search API chưa có. | Binh/Sang chốt request startAt/endAt/guestCount và response availability riêng. Không dùng activeRoomCount làm availableRoomCount. Chốt method/path trước khi mở matcher Security. |
| Room → Pricing / Booking | Pricing có `RoomTypePricingPort.RoomTypeInfo(Long, BigDecimal, int, boolean)`; Booking có record riêng với name và maxOccupancy short. | Sang/Binh/Đình Đạt map field rõ ràng; không cast hai record và không chuyển entity Room ra public API. Long chưa tạo shared record mới vì chưa cần đổi source consumer. |
| Timezone / nights | Quote dùng `OffsetDateTime.toLocalDate()` của offset input; Booking đổi Instant sang Asia/Ho_Chi_Minh. | Binh/Đình Đạt thống nhất lịch theo múi giờ khách sạn. Cùng hai instant phải cho cùng số đêm dù client gửi offset khác; cần test qua mốc nửa đêm. Chưa sửa thuật toán của owner. |
| Tiền trên wire | `QuoteResponse`/`BookingResponse` dùng BigDecimal mặc định JSON number; `docs/pricing/api-contract.md` lại mô tả string. | Đình Đạt/Binh/Thông Đạt chốt một kiểu. Hiện giữ code number để tránh breaking change; không đổi sang string riêng lẻ. Java dùng BigDecimal, tỷ lệ là fraction, so sánh giá trị dùng compareTo khi scale khác. |
| Snapshot giá | Booking tính snapshot riêng, Quote dùng hai ports; chưa có luồng adapter thật. | Owner chốt nguồn giá/discount thống nhất. Booking phải tính/kiểm lại server-side khi tạo hold; không tin số tiền/tier từ FE hoặc tính lại booking cũ khi tier thay đổi. |
| Booking → Payment | Response Booking có cả `id` và `bookingCode`; route hiện dùng bookingCode; payment_attempts FK dùng booking_id. | Binh/Đình Đạt chọn identifier public rồi resolve id ở server. Không đặt bookingCode vào booking_id. Payment lấy payable_amount đã lưu; status payment SUCCEEDED khác status booking CONFIRMED. |
| Room errors / shared errors | Room có `{status,error,message,timestamp}`, Auth/Booking/Pricing có `{code,message,details}`. | Owner thống nhất chuyển đổi hoặc version; FE cần xử lý đúng envelope hiện tại. Không đổi response Room hàng loạt trong đợt security. |
| RoomType update | DTO create/update đều có code, nhưng update service không đổi code; duplicate check create trước strip/uppercase. | Sang chốt code immutable hay editable và quy tắc validation/duplicate. Ghi nhận audit, Long không hoàn thiện CRUD thay Sang. |

Ví dụ khác biệt timezone cần kiểm tra khi Pricing/Booking sẵn sàng: `2026-11-01T23:30:00Z` đến `2026-11-02T03:00:00Z` khác ngày UTC nhưng cùng ngày tại khách sạn. Phải thống nhất từ chối 0 đêm hay quy tắc khác, không để Quote/Booking quyết định khác nhau.

## 4. Đề xuất handoff Payment — chưa có implementation

Owner M4-03: Đình Đạt, phối hợp Binh. Payment hiện chỉ có package placeholder. Các cấu trúc dưới đây là đề xuất để review, không phải DTO/endpoint production đã tạo.

- Request demo đề xuất có `bookingCode`, `idempotencyKey`; không nhận userId/tier/amount authoritative từ client.
- Backend resolve bookingId, kiểm tra Customer owner, trạng thái hold và đọc `payable_amount`; dùng provider `DEMO`.
- Result cần phân biệt `bookingId`, `bookingCode`, `paymentAttemptId`, `provider`, `status`, `requestedAmount`, `succeededAt`, khớp bảng payment_attempts.
- Trạng thái Payment theo V1: PENDING/SUCCEEDED/FAILED; Booking theo V1: PENDING_PAYMENT/CONFIRMED/COMPLETED/CANCELLED/EXPIRED.
- V1 đã có UNIQUE `(booking_id,provider,idempotency_key)`, giao dịch provider và một success/booking. Đây là constraint schema, chưa phải bằng chứng payment idempotency đã được triển khai.
- Chỉ server xác nhận thành công mới chuyển CONFIRMED; FE return URL không phải bằng chứng. Tranh chấp thanh toán sát hạn cần owner thiết kế transaction/state transition và test sau.

## 5. Kiểm tra và giới hạn bàn giao

Backend compile, các test security/contract liên quan và full Maven verify được chạy cho thay đổi của Long; kết quả cuối ghi trong báo cáo bàn giao. Không thêm test bị skip/disabled, probe controller hoặc fake payment/quote endpoint vào production.

Kết quả local ngày 19/10/2026:

| Kiểm tra | Kết quả |
| --- | --- |
| `.\mvnw.cmd -B -ntp -DskipTests compile` | PASS, Java release 21 |
| Targeted Security/Loyalty lần đầu | PASS 94 ca |
| `RbacIntegrationTest` sau bổ sung multi-role | PASS 33 ca (21 cũ + 12 mới) |
| `MembershipBenefitsContractTest` | PASS 7 ca mới |
| `.\mvnw.cmd -B -ntp verify` | PASS 160 ca, 0 failed/errors/skipped, package JAR thành công |
| `frontend/npm test` | PASS 36 ca, source FE không đổi |

Maven verify dùng PostgreSQL 17 tạm riêng cho test, migrate/validate V1/V2; không đụng dữ liệu dev. Không thay dependency, migration hoặc stack Java 21/Spring Boot 4.1.1. Kết quả CI của commit push xem workflow trên nhánh BaoLong; không dùng CI baseline dev để tuyên bố CI của code mới.

Không nghiệm thu Booking concurrency, Payment idempotency hay API Staff Calendar của Sprint 2 trong lần này. Các test đã có ở baseline được chạy regression để phát hiện thay đổi ngoài ý muốn, không dùng để đánh dấu các task Sprint 2 hoàn tất. Checklist tương lai có điều kiện bắt đầu rõ ràng trong tài liệu đi kèm.
