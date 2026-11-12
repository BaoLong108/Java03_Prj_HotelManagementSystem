# Pricing API – `GET /api/v1/pricing/quote`

> Cập nhật 26/10/2026: Room/Loyalty adapter và public quote đã tích hợp thật. Contract đang chạy dùng JSON number cho BigDecimal của Quote/Booking, giữ serializer hiện có; ví dụ chuỗi số và các dependency còn chờ bên dưới là bản đề xuất lịch sử. Số đêm tính sau khi đổi cả hai mốc về `Asia/Ho_Chi_Minh`. Xem [contract Sprint 2 hiện tại](../sprint2-completion.md).

> Tài liệu đề xuất, dành riêng cho module **pricing**. File [`docs/api-contract.md`](../../api-contract.md)
> là quy ước chung; tài liệu này **không sửa** file chung mà chỉ bổ sung chi tiết cho một endpoint.
> Khi nhóm thống nhất, nội dung sẽ được đưa vào `docs/api-contract.md`.

- **Sprint**: 1
- **Task**: M4-01
- **Owner**: Nguyễn Đình Đạt (pricing)
- **Trạng thái**: endpoint đã có code + 9/9 unit test PASS. Tích hợp thực tế đang chờ
  `shared/config/SecurityConfig` mở rule cho Guest (do Long phụ trách) và
  `room` adapter (do Sang phụ trách).

## 1. Tổng quan

Trả về **báo giá tạm thời** cho một loại phòng theo khoảng ngày và số khách. Không ghi DB,
không giữ chỗ, không đụng `physical_rooms` hay `bookings`. Frontend gọi endpoint này trước
khi customer quyết định tạo hold.

- **Phương thức / URL**: `GET /api/v1/pricing/quote`
- **Quyền truy cập**: mở cho **Guest** (chưa đăng nhập, hạng `STANDARD`) **và**
  **Customer/Staff/Admin** đã đăng nhập. Hạng loyalty đọc từ server, **không tin client**.
- **Idempotent**: GET không đổi trạng thái. Có thể gọi nhiều lần với cùng tham số.
- **Không cache**: phản hồi có thể thay đổi khi giá loại phòng hoặc tier của user đổi.

## 2. Query parameters

| Tên | Kiểu | Bắt buộc | Ràng buộc | Mô tả |
| --- | --- | --- | --- | --- |
| `roomTypeId` | number (`Long`) | có | `@NotNull @Positive` | ID của bản ghi `room_types`. |
| `startAt` | ISO 8601 `OffsetDateTime` | có | `@NotNull` | Thời điểm nhận phòng, có offset. Mặc định 12:00. |
| `endAt` | ISO 8601 `OffsetDateTime` | có | `@NotNull` | Thời điểm trả phòng, có offset. Mặc định 10:00. Phải sau `startAt`. |
| `guestCount` | integer | có | `@Min(1) @Max(20)` | Số khách. Không vượt quá `max_occupancy` của loại phòng. |

> **Lưu ý về cách tính đêm**: số đêm = `toLocalDate(endAt) - toLocalDate(startAt)`. Hai mốc
> chỉ khác giờ trong cùng một ngày cho kết quả 0 đêm → 400 `INVALID_NIGHTS`.

Ví dụ URL:

```
GET /api/v1/pricing/quote
    ?roomTypeId=1
    &startAt=2026-11-01T12:00:00+07:00
    &endAt=2026-11-03T10:00:00+07:00
    &guestCount=2
```

## 3. Response thành công – `200 OK`

```json
{
  "roomTypeId": 1,
  "nights": 2,
  "basePricePerNight": 1000000.00,
  "baseAmount": 2000000.00,
  "discountRate": 0.1000,
  "discountAmount": 200000.00,
  "payableAmount": 1800000.00,
  "appliedTier": "VIP"
}
```

| Trường | Kiểu | Quy ước |
| --- | --- | --- |
| `roomTypeId` | number (`Long`) | ID đã báo giá, lặp lại từ input. |
| `nights` | integer | Số đêm lưu trú, ≥ 1. |
| `basePricePerNight` | number (`BigDecimal`, scale 2) | Giá gốc 1 đêm từ `room_types.base_price_per_night`. |
| `baseAmount` | number (`BigDecimal`, scale 2) | Tổng giá hiệu lực của các đêm trong khoảng nhận đến trước ngày trả. |
| `discountRate` | number (`BigDecimal`, scale 4) | `0.0000` / `0.1000` / `0.1500`. |
| `discountAmount` | number (`BigDecimal`, scale 2) | `baseAmount × discountRate`, làm tròn HALF_UP. |
| `payableAmount` | number (`BigDecimal`, scale 2) | `baseAmount - discountAmount`. |
| `appliedTier` | string enum | `"STANDARD"` / `"VIP"` / `"DIAMOND"`. |

> Backend tính tiền bằng `BigDecimal` và trả các trường trên dưới dạng số JSON. Frontend hiển thị số tiền từ báo giá. Backend tính và lưu giá snapshot khi tạo booking.

## 4. Công thức tính giá (VND)

```
nights           = toLocalDate(endAt) − toLocalDate(startAt)        // ChronoUnit.DAYS
baseAmount       = sum(effectiveRate(night))                        // scale 2, HALF_UP
discountRate     = lookup(tier)                                     // 0.0000 / 0.1000 / 0.1500, scale 4
discountAmount   = baseAmount × discountRate                        // scale 2, HALF_UP
payableAmount    = baseAmount − discountAmount                      // scale 2, HALF_UP
```

Mỗi đêm lấy ngày tại `Asia/Ho_Chi_Minh`. Giá hiệu lực lấy theo thứ tự giá chỉ định, ngày lễ, theo mùa, cuối tuần, rồi giá cơ bản. Cuối tuần gồm thứ Bảy và Chủ nhật. Mỗi quy tắc đặt một giá tuyệt đối cho một loại phòng trong khoảng tối đa 366 đêm, tính cả hai đầu. PostgreSQL chặn quy tắc cùng loại đang hoạt động chồng ngày. Lịch giá và preview dùng cùng thứ tự với quote. `basePricePerNight` vẫn là giá cơ bản của RoomType. Xem [OpenAPI vận hành](../operations-openapi.yaml).

Công thức này áp dụng cho **Quote trước mã**. Sau khi tạo hold, Customer có thể [áp mã đổi điểm](../loyalty-vouchers.md) ở checkout; Booking lúc đó có `voucherDiscountAmount`, `afterMembershipAmount` và `payableAmount` cuối sau mã. Payment đọc số cuối từ Booking, không dùng lại Quote hoặc tính lại tier để thu tiền.

Bảng hạng (tham khảo `pricing-rules.md`):

| `appliedTier` | `discountRate` |
| --- | --- |
| `STANDARD` | `0.0000` |
| `VIP` | `0.1000` |
| `DIAMOND` | `0.1500` |

Ngưỡng lên hạng không nằm trong M4-01; tỷ lệ giảm cố định theo tier là đủ để Quote trả về
đúng giá. M4-02 (loyalty) sẽ tự quyết định điều kiện lên hạng.

## 5. Quy tắc nghiệp vụ áp dụng trong M4-01

1. **Không hardcode giá phòng.** `basePricePerNight` luôn lấy từ port `RoomTypePricingPort`,
   không từ biến tĩnh hay file cấu hình. Stub chỉ dùng trong test/dev; xem
   `pricing-rules.md` mục "Cổng triển khai".
2. **Không tin client về tier.** `appliedTier` đọc từ JWT subject → `LoyaltyPricingPort`.
   Guest chưa đăng nhập mặc định `STANDARD`. Không nhận tier từ query string hay body.
3. **Không dùng stub làm dữ liệu production.** Bean stub trong `PricingPortConfiguration`
   chỉ được kích hoạt khi `app.pricing.use-stub-ports=true` (mặc định `false`). Khi
   production, thiếu bean thật sẽ khiến app không khởi động được, đúng theo
   nguyên tắc fail-fast.
4. **Kiểm tra sức chứa.** Nếu `guestCount > roomType.maxOccupancy` trả 400
   `GUEST_COUNT_EXCEEDS_CAPACITY`.
5. **Số đêm tối thiểu 1.** Hai mốc trong cùng ngày trả 400 `INVALID_NIGHTS`.
6. **`startAt` trước `endAt`.** Ngược lại trả 400 `INVALID_DATE_RANGE`.
7. **Bất biến tiền.** `payableAmount == baseAmount − discountAmount` luôn đúng, khớp với
   constraint `ck_bookings_amounts` ở V1.

## 6. Mã lỗi

Lỗi tuân theo `ApiError` của `shared/api`:

```json
{ "code": "MÃ_LỖI", "message": "...", "details": { "field": "..." } }
```

| HTTP | `code` | Khi nào | Nguồn kiểm tra |
| --- | --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Một query param vi phạm Bean Validation (`@NotNull`, `@Positive`, `@Min`, `@Max`) | `MethodArgumentNotValidException` → `ApiExceptionHandler` |
| 400 | `INVALID_DATE_RANGE` | `startAt >= endAt` | `QuoteService.validateRequest` |
| 400 | `INVALID_NIGHTS` | Cùng `LocalDate` → `nights = 0` | `QuoteService` |
| 400 | `GUEST_COUNT_EXCEEDS_CAPACITY` | `guestCount > maxOccupancy` | `QuoteService` |
| 401 | `UNAUTHORIZED` | Token không hợp lệ / thiếu token khi đã yêu cầu xác thực (hiện tại Guest được phép) | `SecurityConfig` |
| 403 | `FORBIDDEN` | Không đủ role (hiện tại không áp dụng cho quote) | `SecurityConfig` |
| 404 | `ROOM_TYPE_NOT_FOUND` | `room_types.id` không tồn tại hoặc `is_active = false` | `RoomTypePricingPort.findActiveById` |
| 500 | `INTERNAL_ERROR` | Lỗi không lường trước | `ApiExceptionHandler` |

## 7. Tích hợp & phụ thuộc

- **Phụ thuộc Sang (room)**: cần một bean implement `RoomTypePricingPort`, đọc
  `room_types.base_price_per_night`, `max_occupancy`, `is_active`. Khi bean thật tồn tại,
  Spring sẽ tự ưu tiên bean đó, stub trong `PricingPortConfiguration` bị bỏ qua (khi cấu hình
  `app.pricing.use-stub-ports=false`).
- **Phụ thuộc Long (loyalty)**: cần một bean implement `LoyaltyPricingPort` đọc
  `customer_profiles.tier_code` theo `userId`. Nếu user không có `customer_profiles` (STAFF,
  ADMIN), trả `STANDARD` để Quote vẫn chạy.
- **Phụ thuộc Long (shared/config/SecurityConfig)**: hiện `anyRequest().denyAll()` đang chặn
  cả `/api/v1/pricing/quote`. Cần thêm rule `permitAll()` cho `GET /api/v1/pricing/quote`
  để Guest xem được. Theo ownership, file này thuộc quyền Long — M4-01 không sửa trực tiếp.

## 8. Ví dụ request/response

### a) Guest, 2 đêm, STANDARD

Request:

```
GET /api/v1/pricing/quote?roomTypeId=1&startAt=2026-11-01T12:00:00%2B07:00&endAt=2026-11-03T10:00:00%2B07:00&guestCount=2
```

Response 200:

```json
{
  "roomTypeId": 1,
  "nights": 2,
  "basePricePerNight": 1000000.00,
  "baseAmount": 2000000.00,
  "discountRate": 0.0000,
  "discountAmount": 0.00,
  "payableAmount": 2000000.00,
  "appliedTier": "STANDARD"
}
```

### b) Customer VIP, 3 đêm

Response 200:

```json
{
  "roomTypeId": 1,
  "nights": 3,
  "basePricePerNight": 1000000.00,
  "baseAmount": 3000000.00,
  "discountRate": 0.1000,
  "discountAmount": 300000.00,
  "payableAmount": 2700000.00,
  "appliedTier": "VIP"
}
```

### c) Không tìm thấy loại phòng

Response 404:

```json
{ "code": "ROOM_TYPE_NOT_FOUND", "message": "Loại phòng không tồn tại" }
```

### d) `guestCount` vượt sức chứa

Response 400:

```json
{ "code": "GUEST_COUNT_EXCEEDS_CAPACITY", "message": "Số khách vượt quá sức chứa của loại phòng" }
```

## 9. Kiểm thử

- File: `backend/src/test/java/com/hotelpms/pricing/service/QuoteServiceTest.java`.
- 9 test hiện tại PASS (xem `target/surefire-reports/`).
- Khi Sang/Long cung cấp adapter thật, bổ sung thêm integration test tầng controller + security.
