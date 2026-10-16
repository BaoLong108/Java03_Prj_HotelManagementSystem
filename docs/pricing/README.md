# Pricing module – README

> Module `backend/pricing` thuộc Sprint 1, task **M4-01**. Owner: **Nguyễn Đình Đạt**.

## Trạng thái M4-01 (tính đến hiện tại)

- ✅ `QuoteService` tính báo giá: `baseAmount`, `discountRate`, `discountAmount`,
  `payableAmount` đều dùng `BigDecimal`, scale 2/4, làm tròn `HALF_UP`.
- ✅ DTO `QuoteRequest` / `QuoteResponse` thống nhất tên trường với cột `bookings` (V1).
- ✅ Port `RoomTypePricingPort` và `LoyaltyPricingPort` đã định nghĩa, **chưa có adapter thật**.
- ✅ Controller `GET /api/v1/pricing/quote` đọc `customerId` từ JWT, không tin client.
- ✅ Stub bean cho dev/test; **không phải dữ liệu production**.
- ✅ 9/9 unit test PASS trong `QuoteServiceTest`.

## Cấu trúc thư mục

```
backend/src/main/java/com/hotelpms/pricing
├── config/
│   └── PricingPortConfiguration.java   # Stub bean, chỉ dev/test
├── controller/
│   └── QuoteController.java            # GET /api/v1/pricing/quote
├── dto/
│   ├── QuoteRequest.java               # @NotNull/@Positive/@Min/@Max
│   └── QuoteResponse.java              # baseAmount, discountRate, ...
├── port/
│   ├── LoyaltyPricingPort.java         # do Long (loyalty) implement
│   └── RoomTypePricingPort.java        # do Sang (room) implement
├── service/
│   └── QuoteService.java               # Logic chính
└── package-info.java

backend/src/test/java/com/hotelpms/pricing
└── service/
    └── QuoteServiceTest.java           # 9 test PASS
```

## Cách chạy test

```powershell
cd backend
.\mvnw.cmd -q -o test -Dtest='QuoteServiceTest'
```

Kết quả hiện tại: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`.

## Phụ thuộc còn chờ

| Phụ thuộc | Người chịu trách nhiệm | Điều cần làm |
| --- | --- | --- |
| Adapter `RoomTypePricingPort` | Lê Phước Sang (room) | Đọc `room_types`, ném 404 nếu không tìm/active. |
| Adapter `LoyaltyPricingPort` | Nguyễn Ngọc Bảo Long (loyalty) | Đọc `customer_profiles.tier_code`; trả `STANDARD` khi user không có profile. |
| Mở `SecurityConfig` cho quote | Nguyễn Ngọc Bảo Long (shared) | Thêm rule `permitAll()` cho `GET /api/v1/pricing/quote` để Guest xem được. |

Cho đến khi ba mục trên xong, app không thể start vì thiếu bean thật (đúng theo nguyên tắc
fail-fast) — **đây là điểm dừng tích hợp M4-01 đã chủ động dừng**.

## Tài liệu liên quan

- [`api-contract.md`](./api-contract.md) — đề xuất tài liệu endpoint `GET /api/v1/pricing/quote`.
- [`pricing-rules.md`](./pricing-rules.md) — quy tắc giá, validation, port.
- [`../../api-contract.md`](../../api-contract.md) — quy ước API chung (do cả nhóm sở hữu).
- [`../../architecture.md`](../../architecture.md) — quy tắc kiến trúc.
- [`../../database.md`](../../database.md) — schema V1, cột `bookings`.

## Phạm vi M4-01 (đã chốt)

- ✅ Endpoint quote dùng `BigDecimal` ở tất cả trường tiền.
- ✅ Đặt tên trường khớp `bookings` (`baseAmount`, `discountRate`, `discountAmount`,
  `payableAmount`).
- ✅ Không hardcode giá phòng.
- ✅ Không dùng stub làm dữ liệu production.
- ❌ **KHÔNG** thuộc M4-01: thanh toán, VNPay, PDF, email, hold, booking.
