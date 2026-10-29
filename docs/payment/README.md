# M4-04 — PaymentService Demo

> Module `payment` — triển khai M4-04 trên nhánh `NguyenDinhDat`.
> Tài liệu này mirror format của `docs/pricing/README.md` (M4-01).
>
> **Tác giả:** Nguyễn Đình Đạt

---

## 1. Trạng thái

| Hạng mục | Trạng thái |
|---|---|
| `PaymentController` (2 endpoint) | ✅ Đã tạo |
| `PaymentService` (idempotency + transaction) | ✅ Đã tạo |
| `PaymentGatewayPort` + cổng DEMO giả lập | ✅ Đã tạo |
| `BookingPaymentPort` (interface + DTO) | ✅ Đã tạo — **chưa có adapter production** |
| Kiểm tra quyền sở hữu (chống IDOR) | ✅ Đã tạo — xem §9 |
| Adapter `BookingPaymentPort` thật | ⏳ **Chờ Phan Thanh Bình** |
| VNPay sandbox / webhook | ⛔ M4-05 — không làm trong M4-04 |
| PDF / Email | ⛔ Ngoài phạm vi M4-04 |

---

## 2. Cấu trúc thư mục

```
backend/src/main/java/com/hotelpms/payment/
├── config/
│   └── PaymentConfiguration.java      Đăng ký bean stub (profile + property)
├── controller/
│   └── PaymentController.java         REST API
├── dto/
│   ├── CreatePaymentRequest.java      Body của POST /api/v1/payments
│   └── PaymentAttemptResponse.java    Body trả về
├── entity/                             (M4-03 — không đổi)
├── gateway/
│   └── DemoPaymentGateway.java         Cổng thanh toán giả lập DEMO
├── port/
│   ├── BookingPaymentPort.java         Interface + record BookingPaymentInfo
│   └── PaymentGatewayPort.java         Interface + record GatewayRequest/GatewayResult
├── repository/                         (M4-03 — không đổi)
└── service/
    └── PaymentService.java             Orchestration: idempotency + transaction
```

---

## 3. Endpoint

### `POST /api/v1/payments`

Tạo lần thử thanh toán demo.

**Request body:**

```json
{
  "bookingId": 1001,
  "idempotencyKey": "8f2c1b9e-...",
  "amount": 1200000.00,
  "simulatedOutcome": "SUCCESS"
}
```

| Trường | Bắt buộc | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `bookingId` | ✅ | `> 0` | ID booking cần thanh toán |
| `idempotencyKey` | ✅ | 1–100 ký tự | Khóa chống ghi lặp do **client** sinh |
| `amount` | ✅ | `>= 0`, tối đa 13 chữ số + 2 thập phân | Số tiền thanh toán |
| `simulatedOutcome` | ❌ | `SUCCESS` \| `FAILURE` | Chỉ dùng ở môi trường demo (xem §5) |

**Response body** (`PaymentAttemptResponse`):

```json
{
  "id": 501,
  "bookingId": 1001,
  "provider": "DEMO",
  "status": "SUCCEEDED",
  "requestedAmount": 1200000.00,
  "providerTransactionId": "DEMO-1001-8f2c1b9e-...",
  "succeededAt": "2026-10-29T09:40:00Z"
}
```

| Mã | Ý nghĩa |
|---|---|
| `201 Created` | Attempt **mới** được tạo |
| `200 OK` | Attempt **đã tồn tại** được tái sử dụng (idempotent) |

> ⚠️ **Chỉ chủ booking được tạo attempt cho booking của mình.** Người gọi khác nhận
> `404 BOOKING_NOT_FOUND` và **không** có attempt nào được tạo. Xem §9.

### `GET /api/v1/payments/attempts/{id}`

Xem trạng thái một lần thử thanh toán. Trả về body `PaymentAttemptResponse` giống trên.

> ⚠️ **Chỉ chủ booking được xem attempt của mình.** Người gọi khác nhận
> `404 PAYMENT_ATTEMPT_NOT_FOUND` (không tiết lộ sự tồn tại của attempt). Xem §9.

---

## 4. ⚠️ Cảnh báo 403 từ `SecurityConfig`

> **`SecurityConfig` hiện tại có thể trả HTTP 403 `FORBIDDEN` cho CẢ HAI endpoint.**

`shared/config/SecurityConfig` đang dùng:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/**").permitAll()
    ...
    .anyRequest().denyAll())
```

Chưa có rule nào cho `/api/v1/payments/**`, nên request tới hai endpoint trên sẽ bị
`denyAll` chặn **trước khi** tới controller.

**M4-04 không sửa `SecurityConfig`** — file này thuộc phạm vi sở hữu của
**Nguyễn Ngọc Bảo Long** (module `shared`). Endpoint sẽ chạy được sau khi quyền
truy cập được mở.

Đây cũng là tình huống **giống hệt** `GET /api/v1/pricing/quote` đã được ghi nhận
tại `docs/pricing/README.md` ("điểm dừng tích hợp M4-01 đã chủ động dừng").

**Hệ quả:** ở môi trường mặc định, `POST /api/v1/payments` trả 403 **trước khi**
tới lớp service — kể cả khi stub đã bật.

---

## 5. Stub Demo — điều kiện kích hoạt

Cổng thanh toán giả lập (`DemoPaymentGateway`) **chỉ** được tạo khi **CẢ HAI**
điều kiện sau thỏa mãn:

| # | Điều kiện | Cơ chế |
|---|---|---|
| 1 | Profile đang chạy là `dev` hoặc `test` | `@Profile({"dev", "test"})` + guard runtime `AllowedDemoProfileCondition` |
| 2 | `APP_PAYMENT_USE_STUB_PORTS=true` | `@ConditionalOnProperty(prefix="app.payment", name="use-stub-ports", havingValue="true")` |

**Mặc định là TẮT.** Property `app.payment.use-stub-ports` **không** được khai báo
trong bất kỳ file cấu hình nào của M4-04 (`application.yml`, `application-dev.yml`
không bị sửa), nên nếu không bật thủ công thì stub không bao giờ tồn tại.

**Cách bật thủ công (dev):**

```bash
APP_PAYMENT_USE_STUB_PORTS=true ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

**Không bao giờ** tự bật stub ở môi trường mặc định hoặc production — hai lớp kiểm
tra độc lập (`@Profile` và guard runtime) phải cùng vượt mới có stub.

### 5.1. `simulatedOutcome` không điều khiển được kết quả thật

Client có thể gửi `simulatedOutcome`, nhưng tầng service kiểm tra
`PaymentGatewayPort.supportsOutcomeSimulation()`:

- Cổng giả lập DEMO → `true` → chấp nhận, truyền xuống gateway.
- Cổng thật (sẽ có ở M4-05) → `false` → **HTTP 400
  `SIMULATED_OUTCOME_NOT_ALLOWED`**.

Nhờ vậy client **không thể** điều khiển kết quả thanh toán thật, kể cả khi đoán
đúng tên endpoint.

### 5.2. Hành vi `DemoPaymentGateway`

| Nội dung | Giá trị |
|---|---|
| Kết quả mặc định | `SUCCESS` (không random) |
| Kết quả theo yêu cầu | `SUCCESS` / `FAILURE` theo `simulatedOutcome` |
| `providerTransactionId` | **Tất định**: `DEMO-{bookingId}-{idempotencyKey}`; nếu vượt 100 ký tự thì thay phần key bằng **hash SHA-512** (vẫn tất định, không va chạm) |
| Hiệu ứng phụ | Không ghi bảng khác, không gọi module ngoài |
| `VNPAY` | Không có adapter — M4-05 |

> **Vì sao mã giao dịch phải tất định?** Cột `provider_transaction_id` có partial
> unique index `uq_payment_attempts_provider_transaction`. Nếu sinh `UUID` ngẫu
> nhiên thì mọi lần gọi lại gateway sẽ vi phạm ràng buộc DB.
>
> **Vì sao dùng hash thay vì cắt bớt chuỗi?** Nếu cắt bớt ở 100 ký tự, hai
> `idempotencyKey` dài khác nhau có thể cho ra **cùng một** mã giao dịch → vi phạm
> unique index. Hash SHA-512 giữ tính tất định và gần như không va chạm.

---

## 6. Mã lỗi

| Mã lỗi | HTTP | Ý nghĩa |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Thiếu/sai định dạng trường bắt buộc (Bean Validation) |
| `SIMULATED_OUTCOME_NOT_ALLOWED` | 400 | Gửi `simulatedOutcome` tới cổng không hỗ trợ mô phỏng |
| `FORBIDDEN` | 403 | Chưa mở quyền truy cập trong `SecurityConfig` (xem §4) |
| `BOOKING_NOT_FOUND` | 404 | Adapter booking trả `Optional.empty()` **hoặc** người gọi không phải chủ booking (xem §9) |
| `PAYMENT_ATTEMPT_NOT_FOUND` | 404 | Attempt không tồn tại **hoặc** người gọi không sở hữu booking của attempt (xem §9) |
| `UNAUTHORIZED` | 401 | JWT không có `sub` hợp lệ ⇒ không xác minh được chủ sở hữu (fail closed) |
| `IDEMPOTENCY_AMOUNT_CONFLICT` | 409 | Cùng `idempotencyKey` nhưng khác `amount` |
| `PAYMENT_AMOUNT_MISMATCH` | 409 | `amount` khác số tiền phải trả của booking |
| `BOOKING_ALREADY_PAID` | 409 | Booking đã có attempt `SUCCEEDED` |
| `IDEMPOTENCY_CONFLICT` | 409 | Race: attempt song song bị rollback, client thử lại |
| `PAYMENT_NOT_AVAILABLE` | 503 | Thiếu `PaymentGatewayPort` hoặc `BookingPaymentPort` |
| `PAYMENT_CONFIRMATION_PENDING` | 503 | Gateway đã phản hồi nhưng trạng thái **chưa lưu được** (xem §8) |

---

## 7. Luồng xử lý & idempotency

### 7.1. Thứ tự kiểm tra (bắt buộc)

```
1. IDEMPOTENCY      ── tra (bookingId, DEMO, idempotencyKey)
   ├─ đã có + cùng amount  → KIỂM TRA CHỦ BOOKING → TRẢ LẠI attempt cũ (200), KHÔNG gọi gateway
   ├─ đã có + khác amount  → 409 IDEMPOTENCY_AMOUNT_CONFLICT
   └─ chưa có               → tiếp tục
2. Gateway khả dụng?      ── không → 503 PAYMENT_NOT_AVAILABLE
3. simulatedOutcome hợp lệ? ── không → 400 SIMULATED_OUTCOME_NOT_ALLOWED
4. Adapter booking có?    ── không → 503 PAYMENT_NOT_AVAILABLE
5. Tra booking             ── không tìm thấy → 404 BOOKING_NOT_FOUND
6. KIỂM TRA CHỦ BOOKING   ── không phải chủ → 404 BOOKING_NOT_FOUND (fail closed)
7. Đối chiếu amount        ── lệch → 409 PAYMENT_AMOUNT_MISMATCH
8. Booking đã SUCCEEDED?   ── có → 409 BOOKING_ALREADY_PAID
9. P1 reserve → P2 gateway → P3 finalize
```

> **Bước 1 đứng trước bước 8.** Retry cùng key sau khi attempt cũ đã `SUCCEEDED`
> nhận lại attempt cũ (200), **không** nhận 409 `BOOKING_ALREADY_PAID`.
>
> **Nhánh replay (bước 1) cũng kiểm tra quyền sở hữu** — xem §9.
>
> **So sánh tiền dùng `BigDecimal.compareTo(...) == 0`**, không dùng `equals`:
> `1000000` và `1000000.00` là khớp (DB lưu `NUMERIC(15,2)`).

### 7.2. Transaction & race condition

`PaymentService` **không** dùng `@Transactional`; dùng `TransactionTemplate` để
tránh self-invocation của proxy:

| Pha | Transaction | Hành động |
|---|---|---|
| **P1 reserve** | tx #1 | insert attempt `PENDING` (`saveAndFlush`) |
| **P2 gateway** | **ngoài transaction** | gọi cổng thanh toán (không giữ khóa ghi khi chờ) |
| **P3 finalize** | tx #2 | ghi mã giao dịch + chuyển trạng thái, **một lần duy nhất** |

**Race (hai request đồng thời cùng key):** cả hai cùng thấy "chưa có" rồi cùng
INSERT; một thắng, một bị DB từ chối bởi `uq_payment_attempts_idempotency` và nhận
`DataIntegrityViolationException` **tại thời điểm flush**.

- Lỗi được bắt **ngoài ranh giới transaction** (quanh `transactionTemplate.execute`)
  → transaction con rollback sạch.
- Sau đó mở **một transaction mới** chỉ để đọc lại attempt mà request song song vừa
  tạo. **Không truy vấn lại trong transaction đã rollback-only** — mọi truy vấn bên
  trong đó đều không đáng tin cậy.
- Không dùng `PESSIMISTIC_WRITE`, không `SELECT … FOR UPDATE`, không advisory lock:
  ràng buộc unique của DB đã là cơ chế phòng tranh chấp chuẩn.

### 7.3. Phân loại lỗi DB ở P1 (quan trọng)

`DataIntegrityViolationException` **không** phải lúc nào cũng là xung đột
idempotency. Service chỉ coi là race khi xác định **đúng** constraint:

| Lỗi DB ở P1 | Phân loại | Kết quả |
|---|---|---|
| Vi phạm `uq_payment_attempts_idempotency` | Race idempotency | Rollback → đọc lại bằng transaction mới → trả attempt cũ (hoặc 409 `IDEMPOTENCY_AMOUNT_CONFLICT` nếu khác số tiền) |
| Vi phạm FK (`payment_attempts_booking_id_fkey`) | Lỗi hạ tầng/dữ liệu | **503 `PAYMENT_NOT_AVAILABLE`** — KHÔNG phải 409 |
| Lỗi kết nối, hạ tầng DB | Lỗi hạ tầng | **503 `PAYMENT_NOT_AVAILABLE`** |
| Vi phạm constraint khác | Lỗi hạ tầng | **503 `PAYMENT_NOT_AVAILABLE`** |

Cơ chế nhận diện: `PaymentService.isViolationOf(exception, "uq_payment_attempts_idempotency")`
dò tên constraint qua `org.hibernate.exception.ConstraintViolationException`
và qua message của exception (chứa nguyên văn lỗi PostgreSQL). **Nếu không xác
định được thì coi là KHÔNG phải constraint đó** — an toàn hơn, không phân loại
nhầm lỗi hạ tầng thành lỗi nghiệp vụ.

Khi vi phạm đúng constraint idempotency nhưng request song song đã bị rollback
(không còn dòng để đọc lại) ⇒ 409 `IDEMPOTENCY_CONFLICT` để client thử lại với
cùng key.

> **Vì sao quan trọng?** Biến lỗi hạ tầng thành 409 `IDEMPOTENCY_CONFLICT` khiến
> client retry vô ích và che mất sự cố hệ thống thật.

---

## 8. ⚠️ Khoảng trống phục hồi trạng thái (quan trọng)

### 8.1. Tình huống

Gateway đã phản hồi kết quả, nhưng pha P3 finalize **không lưu được** (DB lỗi,
mất kết nối…). Attempt vẫn nằm ở `PENDING` trong DB, trong khi gateway có thể
**đã thu tiền**.

### 8.2. Nguyên tắc bảo vệ (đã hiện thực)

| Nguyên tắc | Cách thực hiện |
|---|---|
| **Không tuyên bố `SUCCEEDED`** khi DB chưa lưu `SUCCEEDED` | Trả 503 `PAYMENT_CONFIRMATION_PENDING`; response `status` vẫn là `PENDING` |
| **Không suy luận gateway SUCCESS từ `providerTransactionId`** | Mã giao dịch **không** mang thông tin kết quả — không dùng làm bằng chứng |
| **Không gọi lại gateway** | Không có bằng chứng cổng chưa xử lý → không gọi lại (tránh thu tiền hai lần) |
| **Giữ `PENDING` khi không xác định chắc kết quả** | Không tự chuyển trạng thái một cách phỏng đoán |

### 8.3. Khoảng trống còn lại (cần task sau)

Schema V1 **không có cột lưu outcome của gateway**. Vì M4-04 **không được thêm
migration**, không thể biết chắc chắn attempt `PENDING` đó đã được cổng xử lý
thành công hay chưa.

→ **Cần một cơ chế đối soát ở task sau** (M4-05 hoặc task riêng):

| Hướng xử lý | Mô tả |
|---|---|
| Thêm cột `outcome` | Lưu kết quả gateway ngay khi nhận, đọc lại được khi finalize lỗi. **Cần migration V2.** |
| Job quét đối soát | Job định kỳ quét attempt `PENDING` quá hạn, tra cổng bằng `provider_transaction_id` (repository đã có `findByProviderAndProviderTransactionId`) để xác định kết quả thật |
| Cổng tra cứu trạng thái | M4-05: cổng VNPay hỗ trợ query giao dịch theo mã → dùng để đối soát |

**Ghi nhận đây là khoảng trống đã biết, không phải lỗi ẩn.**

### 8.4. Ranh giới module

`PaymentService` **không** cập nhật booking và **không** chuyển booking sang
`CONFIRMED`. Nguồn sự thật cho "đã thanh toán" ở M4-04 là
`payment_attempts.status = 'SUCCEEDED'`.

---

## 9. 🔒 Quyền sở hữu attempt (đã đóng khoảng trống IDOR)

> **Đã hiện thực trong M4-04.** Cả hai endpoint đều kiểm tra quyền sở hữu dựa
> trên `sub` của JWT (do server ký) — **không** dùng bất kỳ trường nào do client
> kiểm soát trong request body.

### 9.1. Cơ chế

| Bước | Hành vi |
|---|---|
| 1 | `PaymentController` lấy `Long customerId = jwt.getSubject()` (parse `Long`; token không có `sub` hoặc `sub` không phải số ⇒ `null`) |
| 2 | Truyền `customerId` xuống `PaymentService.pay(request, customerId)` / `findAttempt(attemptId, customerId)` |
| 3 | Service tra `BookingPaymentPort.findPayableBooking(bookingId)` để lấy `BookingPaymentInfo.customerId()` |
| 4 | So khớp `customerId()` với user ID từ JWT |
| 5 | **Fail closed** — mọi trường hợp không xác minh được đều bị từ chối, không trả dữ liệu |

### 9.2. Bảng hành vi fail-closed

| Tình huống | `POST /api/v1/payments` | `GET /api/v1/payments/attempts/{id}` |
|---|---|---|
| User là chủ booking | Tiếp tục luồng bình thường | Trả attempt |
| User **không** phải chủ booking | **404 `BOOKING_NOT_FOUND`** | **404 `PAYMENT_ATTEMPT_NOT_FOUND`** |
| JWT không có `sub` / `sub` không phải số | **401 `UNAUTHORIZED`** | **401 `UNAUTHORIZED`** |
| Booking không tồn tại | 404 `BOOKING_NOT_FOUND` | 404 `PAYMENT_ATTEMPT_NOT_FOUND` |
| `customerId` của booking là `null` | **503 `PAYMENT_NOT_AVAILABLE`** | **503 `PAYMENT_NOT_AVAILABLE`** |
| Thiếu `BookingPaymentPort` (chưa có adapter) | **503 `PAYMENT_NOT_AVAILABLE`** | **503 `PAYMENT_NOT_AVAILABLE`** |

> **Vì sao dùng mã "không tìm thấy" thay vì 403?**
> Mã 404 không tiết lộ sự tồn tại của booking/attempt của người khác — người
> gọi không thể dò ra dữ liệu nào tồn tại ngoài phạm vi của mình.

> **Vì sao replay cũng phải kiểm tra quyền sở hữu?**
> Nếu không, kẻ xấu có thể đoán `idempotencyKey` của người khác để đọc attempt
> của họ. Vì vậy nhánh "attempt đã tồn tại" cũng gọi
> `requireOwnedBooking(...)` trước khi trả về.

> **Adapter `BookingPaymentPort` production vẫn chưa có** (chờ Phan Thanh Bình).
> Cho tới lúc đó, endpoint trả 503 `PAYMENT_NOT_AVAILABLE` — **fail closed**,
> không rò rỉ dữ liệu. Xem §10.

---

## 10. 🤝 Việc cần phối hợp với Phan Thanh Bình (module Booking)

M4-04 **không sửa bất kỳ file nào thuộc module Booking**.

### 10.1. Implement `BookingPaymentPort` (bắt buộc)

Module Booking cần implement interface do Payment định nghĩa:

```java
public interface BookingPaymentPort {
    Optional<BookingPaymentInfo> findPayableBooking(Long bookingId);

    record BookingPaymentInfo(
            Long bookingId,
            String bookingCode,
            Long customerId,
            BigDecimal payableAmount
    ) {}
}
```

Yêu cầu khi implement:

| # | Yêu cầu | Ghi chú |
|---|---|---|
| 1 | Tra cứu booking theo `bookingId` | Dùng repository JPA của module booking |
| 2 | Trả `payableAmount` = số tiền khách phải trả | `NUMERIC(15,2)`; Payment so khớp với `requested_amount` |
| 3 | Booking không tồn tại → `Optional.empty()` | Payment trả 404 `BOOKING_NOT_FOUND` |
| 4 | **Không** ném `ApiException` của `shared` trong port | Giữ port độc lập; Payment tự ánh xạ sang lỗi nghiệp vụ của payment |

### 10.2. Câu hỏi cần chốt

| # | Câu hỏi | Vì sao quan trọng |
|---|---|---|
| 1 | Booking ở trạng thái nào thì **không** được thanh toán (vd. `CANCELLED`, `EXPIRED`)? | Hiện Payment **không** kiểm tra trạng thái booking — vì không giả định schema chưa xác nhận |
| 2 | Có cần kiểm tra `hold_expires_at` (giữ chỗ đã hết hạn chưa)? | Ảnh hưởng tới việc gateway đã thu tiền thì xử lý booking ra sao |
| 3 | Booking `CONFIRMED` rồi thì Payment có nên chặn tạo attempt mới không? | Hiện Payment chỉ dựa vào `payment_attempts` |
| 4 | Ai chịu trách nhiệm chuyển booking sang `CONFIRMED` sau khi thanh toán thành công? | M4-04 **không** tự chuyển (ràng buộc phạm vi) |
| 5 | Có cần `BookingPaymentPort` trả thêm thông tin (vd. `holdExpiresAt`) không? | Nếu cần thì **phải sửa record** ⇒ cần thống nhất trước khi implement |

### 10.3. Quy trình tích hợp đề xuất

1. Bình implement `BookingPaymentPort` + đăng ký bean trong module booking.
2. Payment tự động chuyển từ 503 `PAYMENT_NOT_AVAILABLE` sang luồng thật — **không
   cần sửa `PaymentService`**.
3. Kiểm tra quyền sở hữu attempt **đã có sẵn** trong `PaymentService` (§9) — adapter
   chỉ cần trả đúng `customerId` là endpoint chạy được đầy đủ.
4. Chốt câu hỏi #1–#5 ở §10.2.

---

## 11. Chạy test

```bash
cd backend

# Toàn bộ test của module payment
.\mvnw.cmd -o test -Dtest='com.hotelpms.payment.**'

# Chỉ unit test (không cần PostgreSQL)
.\mvnw.cmd -o test -Dtest='PaymentServiceTest,PaymentServiceFinalizeFailureTest,\
DemoPaymentGatewayTest,PaymentControllerTest'
```

Integration test (`PaymentServiceConcurrencyTest`, `PaymentServiceP1RaceTest`) dùng
**PostgreSQL nhúng** (`io.zonky.test.db.postgres.embedded.EmbeddedPostgres`) — không
cần cài PostgreSQL riêng. Có thể trỏ sang DB ngoài bằng biến môi trường
`TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD`.

`PaymentServiceP1RaceTest` dựng race ở pha P1 bằng **PostgreSQL thật**: mở một
connection riêng, insert dòng attempt cùng idempotency key rồi **giữ transaction
chưa commit**; request của service bị PostgreSQL chặn ở unique index cho tới khi
transaction cạnh tranh commit, khi đó insert bị từ chối bằng **lỗi constraint thật**
`uq_payment_attempts_idempotency`. Test xác nhận:
- service rollback rồi đọc lại bằng transaction mới và trả attempt cũ;
- gateway **không** bị gọi lần hai;
- DB chỉ có đúng 1 attempt cho cặp `(bookingId, provider, idempotencyKey)`.

Lỗi FK (booking không tồn tại trong DB) được kiểm chứng là **không** bị biến thành
`IDEMPOTENCY_CONFLICT` mà là 503 `PAYMENT_NOT_AVAILABLE`.

**Lưu ý:** `PaymentControllerTest` dùng `@WebMvcTest` với `addFilters = false` —
nó kiểm tra hợp đồng HTTP, **không** kiểm tra `SecurityConfig` thật. Vì vậy
test này **không** phát hiện được vấn đề 403 ở §4.

---

## 12. Ngoài phạm vi M4-04

| Hạng mục | Trạng thái |
|---|---|
| VNPay sandbox, webhook, `PaymentProvider.VNPAY` | ⛔ M4-05 |
| PDF hóa đơn | ⛔ Ngoài phạm vi |
| Email thông báo | ⛔ Ngoài phạm vi |
| Chuyển booking sang `CONFIRMED` | ⛔ Ngoài phạm vi (module Booking) |
| Sửa `SecurityConfig` | ⛔ Thuộc phạm vi Bảo Long |
| Thêm migration (cột `outcome`) | ⛔ Task sau |
| Sửa `application.yml` / `application-dev.yml` / CI | ⛔ Ngoài phạm vi |
