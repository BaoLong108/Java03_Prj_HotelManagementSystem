# Pricing Rules (M4-01)

> Tài liệu bổ sung cho module **pricing**, mô tả các quy tắc nghiệp vụ và cách Pricing
> trao đổi với module khác qua **port**. File này thuộc phạm vi M4-01 của Nguyễn Đình Đạt.

## 1. Quy tắc giá cơ bản

- **Giá 1 đêm (`basePricePerNight`)** lấy từ `room_types.base_price_per_night` (kiểu
  `NUMERIC(15, 2)` trong DB, `BigDecimal` ở Java). **Không hardcode** trong code.
- **Số đêm** đếm theo `LocalDate`, không theo giờ:
  `nights = ChronoUnit.DAYS.between(startAt.toLocalDate(), endAt.toLocalDate())`.
- **Tỷ lệ giảm** cố định theo tier trong M4-01 (chưa có ngưỡng lên hạng):

  | Tier | `discountRate` |
  | --- | --- |
  | `STANDARD` | `0.0000` |
  | `VIP` | `0.1000` |
  | `DIAMOND` | `0.1500` |

- **Tiền** tính bằng `BigDecimal`, làm tròn `HALF_UP`, scale 2 cho số tiền, scale 4 cho tỷ lệ.
- **Bất biến**: `payableAmount = baseAmount - discountAmount`, khớp với
  `ck_bookings_amounts` ở V1.

## 2. Quy tắc validation

| Điều kiện | HTTP | `code` |
| --- | --- | --- |
| `roomTypeId` null hoặc ≤ 0 | 400 | `VALIDATION_ERROR` |
| `startAt` hoặc `endAt` null | 400 | `VALIDATION_ERROR` |
| `endAt <= startAt` | 400 | `INVALID_DATE_RANGE` |
| Cùng `LocalDate` (nights = 0) | 400 | `INVALID_NIGHTS` |
| `guestCount < 1` hoặc `> 20` | 400 | `VALIDATION_ERROR` |
| `guestCount > maxOccupancy` | 400 | `GUEST_COUNT_EXCEEDS_CAPACITY` |
| `room_types.id` không tồn tại / không active | 404 | `ROOM_TYPE_NOT_FOUND` |

## 3. Cổng triển khai (Ports)

Pricing không import trực tiếp entity của `room` hay `loyalty`. Hai cổng dưới đây cho phép
các module kia tự quyết cách lấy dữ liệu.

### 3.1 `RoomTypePricingPort`

```java
public interface RoomTypePricingPort {
    record RoomTypeInfo(Long id, BigDecimal basePricePerNight, int maxOccupancy, boolean active) {}
    RoomTypeInfo findActiveById(Long roomTypeId);
}
```

Hợp đồng bắt buộc của implementation:

- Trả về `RoomTypeInfo` lấy từ `room_types` với `is_active = true`.
- Nếu không tìm thấy hoặc `is_active = false` → ném `ApiException(404, "ROOM_TYPE_NOT_FOUND", ...)`.
- **Không** trả `null`.

Phụ thuộc: **Lê Phước Sang** (module `room`). M4-01 đã định nghĩa port, chưa viết adapter.

### 3.2 `LoyaltyPricingPort`

```java
public interface LoyaltyPricingPort {
    LoyaltyTier findTierByUserId(Long userId);
}
```

Hợp đồng bắt buộc:

- Trả tier từ `customer_profiles.tier_code` theo `userId`.
- Nếu user không có `customer_profiles` (STAFF/ADMIN) → trả `LoyaltyTier.STANDARD`,
  **không** ném lỗi (vì Guest/nhân viên vẫn xem được quote với giá gốc).
- **Không** trả `null`.

Phụ thuộc: **Nguyễn Ngọc Bảo Long** (module `loyalty`).

## 4. Stub chỉ dành cho dev/test

`PricingPortConfiguration` cung cấp 2 bean stub với `@ConditionalOnMissingBean`. Mục đích:

- **Không dùng làm dữ liệu production.**
- Stub `RoomTypePricingPort` luôn ném 404 `ROOM_TYPE_NOT_FOUND` — không trả giá giả.
- Stub `LoyaltyPricingPort` luôn trả `STANDARD`.

Spring Boot sẽ tự ưu tiên bean thật khi các module khác đăng ký. Stub chỉ "chữa cháy"
khi chạy một mình module pricing trong giai đoạn phát triển.

## 5. Liên hệ với Booking (Bình)

Các trường tiền trong `QuoteResponse` được thiết kế để copy thẳng vào `bookings`:

| `QuoteResponse` | `bookings` (V1) |
| --- | --- |
| `baseAmount` | `bookings.base_amount NUMERIC(15,2)` |
| `discountRate` | `bookings.discount_rate NUMERIC(5,4)` |
| `discountAmount` | `bookings.discount_amount NUMERIC(15,2)` |
| `payableAmount` | `bookings.payable_amount NUMERIC(15,2)` |
| `nights` × `basePricePerNight` | dùng để suy ra `base_amount` nếu cần |

Ràng buộc SQL `ck_bookings_amounts` (`payable_amount = base_amount - discount_amount`) sẽ
bảo vệ phía DB nếu code Booking tính sai — Pricing tự đảm bảo bất biến này ở service.

## 6. Liên hệ với Loyalty (Long)

M4-01 chỉ cần `LoyaltyTier` enum đã có sẵn (`STANDARD`, `VIP`, `DIAMOND`). Long sẽ:

- Tạo entity/repository cho `customer_profiles` (đã có schema ở V1).
- Viết `LoyaltyAdapter` implement `LoyaltyPricingPort`.
- Tính tier dựa trên `eligible_spend_total` (ngưỡng VIP/Diamond chưa chốt trong M4-01).

M4-01 không tự quyết ngưỡng tier; nó chỉ chuyển tier đã resolve thành `discountRate`.

## 7. Phạm vi M4-01 (đã chốt)

- ✅ Định nghĩa `RoomTypePricingPort` và `LoyaltyPricingPort`.
- ✅ Tính quote dựa trên port, công thức chuẩn VND, scale 2/4, HALF_UP.
- ✅ Controller `GET /api/v1/pricing/quote`, đọc `customerId` từ JWT (không tin client).
- ✅ Bean stub cho dev/test, không phải production data.
- ✅ 9/9 unit test PASS cho `QuoteService` (xem `QuoteServiceTest`).
- ⏳ Chờ Sang: adapter `RoomTypePricingPort` thật từ `room`.
- ⏳ Chờ Long: adapter `LoyaltyPricingPort` thật từ `loyalty` và rule mở `SecurityConfig`
  cho `GET /api/v1/pricing/quote`.
