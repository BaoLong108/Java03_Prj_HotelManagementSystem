# Đổi điểm lấy mã giảm giá — phần mở rộng của Long

QA ngày 03/11/2026 đã kiểm tra thanh toán thường, thanh toán 0 VND, hoàn mã và request đồng thời trên dữ liệu demo. Backend 275/275, frontend 85/85, lint, build và Flyway đều pass trong lượt kiểm tra đó.

Yêu cầu bổ sung ngày 02/11/2026, triển khai trên `BaoLong`, tiếp nối M1-06/M1-07. Không thay đổi tỷ lệ giảm giá hạng hoặc cách tạo quote hiện tại.

## Chính sách

| Điểm bị trừ | Giá trị mã |
|---|---|
| 50 | 400.000 VND |
| 100 | 1.000.000 VND |
| 200 | 2.200.000 VND |

- Chỉ Customer đã đăng nhập được đổi và sử dụng mã của chính mình. Không chuyển mã cho tài khoản khác. Một booking áp dụng một mã.
- Mã ngẫu nhiên đúng 6 ký tự, có chữ hoa A–Z và chữ số 0–9. Dùng SecureRandom, UNIQUE trong DB và retry khi va chạm mã. Mã không có ngày hết hạn theo yêu cầu hiện tại.
- Điểm bị trừ khi **đổi mã**; mã chưa dùng được giữ trong ví. Chi tiêu tích lũy và hạng không bị giảm khi đổi điểm. Mỗi lần đổi có idempotencyKey riêng; retry cùng key không trừ điểm lần hai.
- Giá sau hạng = `baseAmount - discountAmount`. Giảm bằng mã = `min(giá trị mã, giá sau hạng)`. `payableAmount = giá sau hạng - voucherDiscountAmount` và không âm.
- Người dùng đã xác nhận: nếu mã vượt tiền phòng, số phải trả bằng 0, **phần dư không hoàn lại**; mã được dùng hết sau thanh toán thành công. Booking 0 VND vẫn xác nhận qua Payment demo và lưu giao dịch thành công 0 VND để kiểm toán vòng đời.
- Ví dụ: phòng 2.000.000 VND, VIP giảm 10% còn 1.800.000; dùng mã 1.000.000 thì thanh toán 800.000. Nếu dùng mã 2.200.000 thì thanh toán 0, không hoàn 400.000 còn dư.
- Chỉ ghi nhận điểm/chi tiêu mới từ **số thực trả cuối cùng sau mã** khi lượt ở COMPLETED. Booking được giảm về 0 ghi ledger 0 điểm/0 chi tiêu, không cộng điểm theo giá gốc.

## Vòng đời và concurrency

`AVAILABLE → RESERVED → USED`.

- Nhập mã ở booking PENDING_PAYMENT còn hạn sẽ RESERVED cho booking đó, cập nhật snapshot giảm mã và tổng cuối. Cùng request áp lại cùng mã cho cùng booking không trừ thêm điểm.
- Gỡ mã trước thanh toán: trả mã về AVAILABLE, tổng booking về giá sau hạng; không đổi snapshot ưu đãi hạng. Muốn đổi mã khác, gỡ mã cũ trước.
- Thanh toán thất bại: mã vẫn RESERVED cho booking, có thể retry. Thanh toán thành công: mã USED trong cùng transaction với Payment và booking CONFIRMED. Booking đã trả tiền không thể gỡ/thay mã.
- Hủy hold/hết hạn: mã chưa dùng trở về AVAILABLE, không hoàn lại điểm đổi mã vì vẫn sở hữu mã đó. Snapshot của booking đã hủy/hết hạn có thể giữ mức giảm đã chọn, nhưng không có tiền được thu hoặc điểm được cộng cho booking đó.
- Tất cả mutation dùng thứ tự khóa **booking → voucher**. Đổi điểm khóa **customer profile**; điểm/row voucher ghi cùng transaction. Hai request không thể làm điểm âm hoặc dùng một mã cho hai booking đồng thời.
- Các đường cancel, job expire, payment/confirm khi hết hạn đều trả mã trực tiếp. Job `VoucherReservationScheduler` kiểm tra cả hold đã được RoomAllocation reclaim bằng SQL, mỗi 60 giây/batch 100. Trước khi áp mã, controller cũng dọn reservation hết hạn của chính khách để có thể dùng lại ngay.
- Bảng `loyalty_vouchers` ghi points_cost/discount_amount/customer/idempotency_key/created_at/booking_id/used_at, dùng làm lịch sử đổi điểm và sử dụng mã. Không tạo API cho client tự cộng điểm hoặc thay giá trị mã.

## API

Tất cả endpoint dưới đây yêu cầu JWT role CUSTOMER; Guest 401, Staff/Admin không có CUSTOMER 403. Không nhận customerId trong request.

### `GET /api/v1/customers/me/vouchers/offers`

```json
[
  { "pointsCost": 50, "discountAmount": 400000.00 },
  { "pointsCost": 100, "discountAmount": 1000000.00 },
  { "pointsCost": 200, "discountAmount": 2200000.00 }
]
```

### `POST /api/v1/customers/me/vouchers/redeem`

```json
{ "pointsCost": 100, "idempotencyKey": "e55c70cc-65b0-44c6-b7af-123456789012" }
```

Trả 201 (kể cả replay cùng yêu cầu) với `code`, `pointsCost`, `discountAmount`, `status`, `bookingId`, `createdAt`, `usedAt`. Client gửi key mới cho ý định đổi tiếp theo; giữ nguyên key nếu mất mạng và retry. Không chấp nhận gói khác 50/100/200 (400); thiếu điểm hoặc tái dùng key cho gói khác trả 409, không ghi/trừ điểm.

### `GET /api/v1/customers/me/vouchers?page=0`

Trả `{content: VoucherResponse[], page, totalPages, totalElements}`, page từ 0, 10 mã/trang, mới nhất trước (`createdAt DESC, id DESC`). `status` là AVAILABLE, RESERVED hoặc USED. Customer chỉ đọc mã của mình; `bookingId` luôn là booking của cùng khách theo FK kép.

### `POST /api/v1/bookings/{bookingCode}/voucher`

```json
{ "code": "A1B2C3" }
```

Trả BookingResponse 200. Giữ các field cũ và thêm:

```json
{
  "baseAmount": 2000000.00,
  "discountRate": 0.1000,
  "discountAmount": 200000.00,
  "afterMembershipAmount": 1800000.00,
  "voucherCode": "A1B2C3",
  "voucherDiscountAmount": 1000000.00,
  "payableAmount": 800000.00
}
```

JSON chỉ minh họa các field giá, không phải toàn bộ BookingResponse. Frontend chuyển input thành chữ hoa; backend yêu cầu đúng 6 ký tự A–Z/0–9. Tài khoản khác thao tác booking trả 403; mã không thuộc khách/đã dùng/đang giữ cho booking khác trả 409; sai định dạng trả 400.

### `DELETE /api/v1/bookings/{bookingCode}/voucher`

Trả BookingResponse 200 với `voucherCode: null`, `voucherDiscountAmount: 0`, `payableAmount: afterMembershipAmount`. Chỉ cho chủ booking còn PENDING_PAYMENT và còn hạn. Có thể gọi lặp khi chưa áp mã mà không làm mất điểm hay thêm tiền.

## Hợp đồng cho Booking/Payment/Pricing

- Quote tìm phòng **chưa áp mã**, giữ công thức cũ; không thêm mã vào QuoteRequest hoặc PriceSnapshot của Pricing. Ưu đãi hạng tại lúc tạo booking không bị tính lại.
- Sau áp mã, `bookings.payable_amount` là số cuối để thu. Không lấy lại quote mới hoặc tự tính từ tier hiện tại khi Payment chạy. Không dùng `baseAmount - discountAmount` làm số thu nếu có voucher.
- `DemoPaymentService` gọi `VoucherService.consumeForPayment` bên trong transaction đang khóa booking, sau validate/nhánh payment failure, trước persist thanh toán thành công. Lỗi DB rollback cả mã, payment và booking. Replay payment trả attempt cũ trước thao tác mã.
- Payment gateway bổ sung sau này (ví dụ VNPay trên nhánh thành viên) phải dùng chung số cuối và bước consume trong transaction confirm thành công; giữ RESERVED khi thất bại. Tổng bằng 0 dùng đường xác nhận 0 VND của demo, không tạo yêu cầu gateway có amount không được gateway hỗ trợ. Phần này cần owner Payment đối chiếu khi merge, chưa tự sửa code ngoài dev của thành viên.
- PDF xác nhận ghi riêng giảm hạng, mã, số giảm bằng mã, số phải trả và thực thu. Staff reads/MyBookings tiếp tục đọc payableAmount cuối từ DB.

## Migration và chạy thử

- `V3__loyalty_vouchers.sql` là migration mới, không sửa V1/V2 và không reset DB. Thêm bảng mã, hai cột `bookings.voucher_code`, `bookings.voucher_discount_amount`, cập nhật constraint số tiền theo công thức mới. Dữ liệu cũ nhận voucherDiscountAmount=0 nên giữ nguyên giá trị.
- Cập nhật backend trước frontend mới. Flyway tự áp dụng V3 khi khởi động; JPA vẫn `ddl-auto=validate`. Không chỉnh checksum migration đã áp dụng.
- UI `/account`: Đổi điểm lấy mã giảm giá → chọn gói → điểm được trừ và mã hiện trong ví. UI `/booking`: sau giữ chỗ, nhập mã → Áp dụng mã → xem tổng mới → Thanh toán Demo. Có Gỡ mã trước khi trả tiền.
- UI xử lý loading/error/retry, khóa nút khi đang đổi/áp mã, giữ redemption key khi mất mạng. Mutation mã làm vô hiệu response polling cũ để giá cũ không ghi đè giá mới; số cuối lấy từ response booking của server.
- Test không cần điểm/đơn cá nhân: dùng fixture PostgreSQL riêng của `LoyaltyIntegrationTest`; không trỏ TEST_DB_URL vào DB demo đang dùng.

## Kiểm tra đã chạy

- Backend **275/275 test PASS**, 40 integration tests Loyalty/Voucher; kiểm tra tất cả gói, thiếu điểm, replay, concurrent redemption/reservation, code collision/retry, net sau VIP, zero total, hủy/hết hạn, failure/retry, PDF và RBAC/ownership. Toàn suite dùng PostgreSQL test riêng và đã dọn DB test.
- Frontend ban đầu **84/84 test PASS**; QA tiếp tục ngày 03/11 sửa nhãn lịch sử thiếu giảm mã và thêm regression, kết quả mới **85/85 PASS**, lint/TypeScript/Vite build PASS. Có kiểm tra đổi điểm/retry cùng key, double click, stale private wallet, form áp/gỡ mã, tổng 0 và hold hết hạn.
- Windows từng giữ JAR của backend cũ đang chạy khiến bước Maven repackage không đổi tên file được. Đã dừng đúng process backend của worktree và chạy package lại thành công; đây không phải test failure. Bản backend mới đã khởi động với PostgreSQL local và Flyway áp dụng V3 thành công.
- Chưa commit/push. Không sửa nhánh thành viên/main/dev. Phần Sprint 3–4 trước đó trên BaoLong vẫn giữ lại.
