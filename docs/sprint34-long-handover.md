# Sprint 3–4 — phần việc Nguyễn Ngọc Bảo Long

Ngày triển khai: 02/11/2026. Baseline: `origin/dev` tại `2c888713cf1a04341febc76cfc3747c400029fca`, cập nhật fast-forward vào `BaoLong`. Không triển khai task của thành viên khác, không thay đổi schema V1/V2, không commit/push trong lần triển khai này.

Nguồn phân công: `outputs/01a11bd1-09bd-7d42-a507-ef5e453c6794/Ke_hoach_PMS_4_sprint_theo_tuan.xlsx`, sheet Sprint 3 hàng 13–14; Sprint 4 hàng 11–12.

| ID | Công việc của Long | Kết quả |
|---|---|---|
| M1-06 | Cộng điểm và nâng hạng | Có ledger `loyalty_awards`, khóa booking và profile, ghi award + tổng chi tiêu + điểm + tier cùng transaction; tích hợp job COMPLETED và đường complete có sẵn. |
| M1-07 | Booking và điểm của chính khách | Giữ API/trang `/my-bookings`; bổ sung API membership và lịch sử điểm phân trang, nối `/account` với API thật. Kiểm tra quyền bằng JWT subject. |
| M1-08 | Kiểm thử đặt, tái bán phòng, block và loyalty | Có HTTP integration test trên PostgreSQL thật với thời gian cố định, kiểm tra giữ chỗ → demo payment → job COMPLETED → tích điểm → đặt lượt kế tiếp cách 2 giờ. Có block → từ chối hold → release → hold thành công. Nghiệm thu thao tác block trên UI còn phụ thuộc M5-05 của Thông Đạt. |
| M1-09 | Compose, seed và demo | Có Dockerfile DB/API/FE, healthcheck + thứ tự khởi động, proxy API/SPA, named volume, seed dev và script smoke. Máy hiện tại chưa có Docker/WSL, nên chưa xác nhận container runtime; CI `compose-demo` sẽ kiểm tra khi người dùng commit/push. |

## Quy tắc loyalty

- Chỉ booking **COMPLETED**, đã qua `endAt`/`completedAt`, có payment **SUCCEEDED** đúng số tiền snapshot và đã đến thời điểm thành công mới đủ điều kiện.
- Một booking ghi ledger một lần. Khóa theo thứ tự **booking → customer profile** để vừa chống hai worker ghi cùng booking, vừa tránh mất tổng khi hai booking của cùng khách hoàn tất đồng thời. `UNIQUE(loyalty_awards.booking_id)` là rào cuối ở database.
- Tiền đủ điều kiện = số thực trả sau giảm giá từ payment khớp `booking.payable_amount`. Điểm = `floor(eligibleAmount / 10000)` cho mỗi lượt ở. VIP từ 10.000.000 VND giảm 10%; Diamond từ 30.000.000 VND giảm 15%; Standard 0%.
- COMPLETED và award cùng transaction; lỗi ghi award/tổng sẽ rollback cả lượt chuyển trạng thái đó để có thể chạy lại. Job reconciliation mỗi 60 giây xử lý tối đa 100 booking COMPLETED chưa có ledger, gồm lượt ở hoàn tất trước khi M1-06 được bổ sung. Khi khởi động bản mới, booking cũ đủ điều kiện có thể được cộng điểm.
- PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED, chưa trả tiền, payment FAILED hoặc sai số tiền không được cộng điểm. Không sửa giá booking cũ khi lên hạng; Pricing adapter hiện có đọc tier mới cho quote tiếp theo.
- Mini PMS hiện không có nghiệp vụ refund. Không cộng điểm cho booking hủy; không thêm refund hoặc hoàn tác điểm giả. Nếu owner Payment bổ sung refund sau này, cần thống nhất ledger reversal trước khi bật nghiệp vụ đó.
- Không có endpoint cho client tự cộng điểm, tự sửa tổng chi tiêu hoặc tier. Không thêm nút check-in/check-out vào UI.

## API bàn giao M1-07

Tất cả request dùng `Authorization: Bearer <accessToken>`. Chỉ tài khoản có role CUSTOMER được gọi; Guest 401, Staff/Admin không có CUSTOMER 403. User id lấy từ JWT đã kiểm tra tài khoản/role trong DB, không nhận customerId đầu vào.

### GET `/api/v1/customers/me/membership`

```json
{
  "tierCode": "VIP",
  "eligibleSpendTotal": 10000000.00,
  "pointsBalance": 1000,
  "discountRate": 0.10,
  "nextTier": "DIAMOND",
  "nextTierSpend": 30000000.00,
  "remainingSpend": 20000000.00
}
```

Diamond trả `nextTier: null`, `nextTierSpend: null`, `remainingSpend: 0`. Chi tiêu tích lũy là chi tiêu của lượt ở hợp lệ đã hoàn tất, không phải mọi booking mới thanh toán.

### GET `/api/v1/customers/me/loyalty-awards?page=0&size=10`

```json
{
  "content": [
    { "bookingCode": "BK-EXAMPLE", "eligibleAmount": 1000000.00, "pointsAwarded": 100, "awardedAt": "2032-01-10T05:00:00Z" }
  ],
  "page": 0, "size": 10, "totalElements": 1, "totalPages": 1
}
```

`page >= 0`, `1 <= size <= 50`, sai trả 400 `INVALID_PAGINATION`. Sắp xếp `awardedAt DESC, id DESC`. Booking history giữ nguyên `GET /api/v1/bookings` đã lọc theo JWT subject; trang `/my-bookings` hiện có được tái sử dụng.

UI `/account` có loading, error/retry, empty sau response thành công, phân trang, tiến độ lên hạng, timezone khách sạn. Request cũ bị abort khi đổi trang/tài khoản; response trễ không ghi đè tài khoản mới. Staff/Admin giữ giao diện công cụ vận hành, không gọi API membership của Customer.

## Chạy và giữ dữ liệu

```sh
docker compose up --build -d --wait
docker compose ps
docker compose logs --tail 100 backend frontend
```

- Web: `http://localhost:5173/login`; backend health: `http://localhost:8080/actuator/health`.
- PostgreSQL 17 dùng named volume `postgres_data`; API dùng Java 21, FE build Node 22 và phục vụ bằng Nginx, proxy cùng origin `/api`. Fixture mode tắt.
- Ba email: `customer@hotel-pms.test`, `staff@hotel-pms.test`, `admin@hotel-pms.test`.
- Database mới dùng mật khẩu demo `HotelPmsDemo123!`. Seed giữ nguyên mật khẩu và booking hiện có. Nếu đổi `DEMO_SEED_PASSWORD`, dùng mật khẩu đáp ứng chính sách tạo tài khoản.
- Dừng bằng `docker compose stop` hoặc `docker compose down`; bật lại bằng lệnh `up`. Không thêm `--volumes` nếu muốn giữ DB. Xóa volume là reset dữ liệu, không phải bước chạy demo.
- `.env.example` chỉ có giá trị demo công khai. `.env` thật bị gitignore. Profile `dev` chỉ phục vụ môi trường demo/local, không phải cấu hình deploy public.
- Compose dùng volume riêng, tách biệt với PostgreSQL chạy trực tiếp trên máy.

## Kịch bản nghiệm thu

1. Customer đăng nhập → `/rooms` → chọn ngày tương lai và loại phòng → quote → giữ chỗ → thanh toán demo → booking CONFIRMED → tải PDF → `/my-bookings` có booking đúng giá snapshot.
2. Vào `/account`: booking vừa thanh toán nhưng chưa kết thúc lượt ở **chưa** làm tăng điểm/chi tiêu.
3. Với lượt ở đã thanh toán, qua `endAt`, job tự COMPLETED và ghi điểm; đợi tối đa khoảng 60 giây rồi bấm **Tải lại** ở tài khoản. Khi đạt ngưỡng, tier/discount đổi; quote mới dùng tier mới, booking cũ không đổi giá.
4. Không chỉnh giờ hệ điều hành, sửa SQL booking đang demo hoặc tạo production endpoint để ép COMPLETED. Muốn chứng minh toàn bộ vòng đời ngay, chạy integration test có fixed instant bên dưới. Nó dùng DB test riêng và gọi chính service chuyển trạng thái đang chạy production.
5. Đặt lượt kế tiếp lúc 12:00 sau lượt trước kết thúc 10:00: phòng được bán tiếp. Cùng khoảng của booking cũ vẫn là lịch sử đã sử dụng, không được bán trùng.
6. Staff mở Calendar/Lookup xem booking thật. Block/release API đã được integration test; chờ M5-05 trước khi nghiệm thu thao tác tương đương trên UI. Customer không vào được API Staff và không xem được điểm/booking của khách khác.

## Kiểm thử

Backend, tại `backend/`:

```powershell
.\mvnw.cmd -B -ntp '-Dtest=LoyaltyIntegrationTest,LoyaltyServiceTest,MembershipBenefitsContractTest,BookingIntegrationTest' test
.\mvnw.cmd -B -ntp verify
```

Tests tự tạo PostgreSQL tạm, không dùng DB demo. Nếu đặt `TEST_DB_URL`, phải trỏ vào **database test riêng có thể reset**, tuyệt đối không trỏ DB chứa tài khoản/booking thật. Có kiểm tra concurrent retry, hai lượt ở đồng thời của cùng khách, rollback ledger/tổng, ngưỡng hạng, quyền sở hữu, pagination và hồi phục ledger cũ.

Frontend, tại `frontend/`:

```powershell
npm ci
npm run lint
npm test
npm run build
```

Smoke HTTP qua frontend proxy, tại gốc repo, chỉ với **DB test/demo**:

```sh
node scripts/smoke-demo.mjs
```

Script tạo một tài khoản Customer riêng và một booking demo đã trả tiền (không tự hủy vì không hỗ trợ refund), kiểm tra replay payment, lịch sử của mình, membership chưa cộng sớm, RBAC và chữ ký PDF. Không in JWT/password. Có thể đổi URL bằng `DEMO_BASE_URL`. Script không giả lập quá hạn lượt ở và không thay thế kiểm thử vòng đời với fixed time.

CI thêm job `compose-demo`: build cả stack → healthchecks → chạy script HTTP → dọn **volume CI dùng một lần**. Trạng thái CI chỉ có sau khi Long tự commit/push. Không được gọi CI PASS khi chưa chạy.

Trong lúc kiểm tra trên DB test dùng chung giống CI, test sắp xếp Payment cũ có thể tạo nhiều attempt cùng timestamp (độ phân giải đồng hồ hệ điều hành). Đã sửa riêng fixture của `findByBookingIdOrderByCreatedAtDesc` và `findFirstByStatusReturnsMostRecent`: gán các `created_at` cố định, cách nhau một giây, thay vì chờ 5ms/giả định lần save sau luôn có timestamp lớn hơn. Không đổi query hoặc production logic Payment.

Đã xác minh lượt ở cũ đủ điều kiện được ghi nhận 2.400.000 VND chi tiêu và 240 điểm khi chạy bản mới. Giá snapshot của booking giữ nguyên.

## Những phần cần người phụ trách bàn giao

- Thông Đạt: M5-05 block/release UI để hoàn tất nghiệm thu UI của M1-08; M5-08 và báo cáo/audit/CSV theo WBS vẫn do Thông Đạt làm.
- Sang: M2-07 danh mục phòng Admin và smoke test M2-08 theo WBS.
- Bình: công việc Booking Sprint 3 riêng; không thay đổi hợp đồng Booking hiện tại trong phần việc này.
- Đình Đạt: VNPay, email, phụ thu cuối tuần tùy ưu tiên WBS; chưa tích hợp code nằm ngoài dev của các nhánh thành viên.

## Kết quả xác minh 02/11/2026

- Backend full regression `mvnw verify`: **252/252 PASS**, 0 fail/error/skip, dùng một PostgreSQL test database riêng theo cùng cơ chế `TEST_DB_URL` của CI. Database test tạo riêng cho lần chạy và đã dọn; DB demo `hotel_pms` giữ nguyên. Trước đó regression trên các PostgreSQL embedded độc lập cũng đã PASS; fixture Payment chập chờn đã sửa như mô tả ở trên.
- Frontend: **76/76 PASS**, lint 0 lỗi/cảnh báo, TypeScript/Vite build PASS; 7 DOM tests mới cho trang tài khoản.
- Smoke qua `http://localhost:5173` → API/DB thật: PASS (register/login, availability/quote, hold, payment/replay, own bookings, membership/RBAC, PDF). Script để lại một tài khoản smoke và một booking demo CONFIRMED trong DB demo.
- Browser: `/account` hiển thị ledger thật, số điểm và tiến độ trên desktop và mobile 393px. Reload vẫn tải đúng dữ liệu.
- Compose: cấu hình được kiểm tra bằng CLI Compose chính thức, `config --quiet` PASS; xác minh checksum SHA-256 của binary kiểm tra, không cài Docker vào hệ thống. **Chưa chạy build/up container** vì máy không có Docker Engine/Desktop/WSL. Job `compose-demo` trên GitHub **chưa chạy**, chờ Long commit/push.
- `git diff --check`: PASS. Không sửa production Payment/Room/Calendar, không đổi migration cũ, không sửa branch thành viên hoặc `main`/`dev`, không commit/push.

Phần code M1-06/M1-07 đã hoàn tất và kiểm thử. M1-08 đã có kiểm thử vòng đời/API; còn nghiệm thu block UI khi Thông Đạt bàn giao M5-05. M1-09 đã chuẩn bị đóng gói/hướng dẫn/CI và xác thực config; còn chạy container thực tế trên máy có Docker trước khi đánh dấu DONE trọn vẹn.
