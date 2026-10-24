# M1-04 / M1-05 — bàn giao Sprint 1

Ngày kiểm tra: 16/10/2026. Chủ nhiệm: Nguyễn Ngọc Bảo Long.

## Phạm vi và trạng thái

Nguồn kế hoạch: `outputs/01a11bd1-09bd-7d42-a507-ef5e453c6794/Ke_hoach_PMS_4_sprint_theo_tuan.xlsx`, sheet `Sprint 1`, dòng 14 (M1-04), 16 (M2-02), 17 (M1-05); M1-06 nằm trong Sprint 3. File kế hoạch không bị chỉnh sửa trong lần triển khai này.

| Task | Kết quả | Phần bàn giao |
| --- | --- | --- |
| M1-04 | **DONE** trong phạm vi calculation service | Tier, discount, điểm, validation, DTO cho Pricing và unit tests |
| M1-05 | **PARTIAL** | Bảo vệ Auth hiện có, method security, policy role/ownership và security tests; chờ API phòng M2-02, RoomBlock và Booking để áp dụng/kiểm thử HTTP thực tế |

Khác biệt giữa mô tả tham khảo và repository/WBS:

- Repository đang dùng Spring Boot **4.1.1**, Spring Security **7.1.1**, Java release **21**; giữ nguyên phiên bản và kiến trúc hiện có.
- Role DB là `CUSTOMER`, `STAFF`, `ADMIN`; authorities tương ứng `ROLE_CUSTOMER`, `ROLE_STAFF`, `ROLE_ADMIN`. Guest là request ẩn danh, không có row hoặc JWT. Không thêm Receptionist/Housekeeping.
- PMS mini dùng booking `COMPLETED`, không có `CHECKED_OUT`, check-in/out hay Folio. Theo quy tắc DB/WBS, M1-06 chỉ ghi điểm sau khi booking **đã thanh toán và COMPLETED**.
- Room, Booking, Payment chưa có business controller/service. Không thêm CRUD, route mẫu hoặc dữ liệu thanh toán giả để làm đầy task.

## M1-04 — contract cho Đình Đạt (Member 4)

Inject `com.hotelpms.loyalty.LoyaltyService` vào Pricing service. Service không ghi database, không cộng điểm và không đổi tier đã lưu.

| Method | Kết quả |
| --- | --- |
| `resolveTier(BigDecimal eligibleSpendTotal)` | `STANDARD` dưới 10 triệu; `VIP` từ 10 triệu đến dưới 30 triệu; `DIAMOND` từ 30 triệu |
| `getDiscountRate(LoyaltyTier tierCode)` | `BigDecimal` tỷ lệ `0.00`, `0.10`, `0.15` |
| `calculateEarnedPoints(BigDecimal eligibleSpent)` | `long`, lấy phần nguyên xuống của `eligibleSpent / 10000` |
| `calculateTierAfterSpending(BigDecimal currentSpent, BigDecimal additionalSpent)` | Tier của tổng chi tiêu mới, không cập nhật profile |
| `getMembershipBenefits(BigDecimal eligibleSpendTotal)` | Record `MembershipBenefits(tierCode, eligibleSpendTotal, discountRate)` |

```java
// profile phải được backend tải theo danh tính đã xác thực.
MembershipBenefits benefits = loyaltyService.getMembershipBenefits(profile.getEligibleSpendTotal());
BigDecimal discountRate = benefits.discountRate(); // Ví dụ 0.10, không phải 10.
LoyaltyTier tierAtBooking = benefits.tierCode();
```

- Dùng chi tiêu **đã ghi nhận trước booking hiện tại**; không cộng trước số tiền booking đang báo giá để nâng hạng.
- Giảm giá chỉ áp dụng phần tiền phòng đủ điều kiện. Pricing chịu trách nhiệm tính/round `discountAmount`, `payableAmount` theo hợp đồng đã chốt.
- Khi tạo booking, lưu snapshot `base_amount`, `discount_rate`, `discount_amount`, `payable_amount` của V1; không tính lại booking cũ khi khách đổi hạng.
- DTO đã trả `tierCode` để Booking có thể giữ snapshot hạng. **V1 chưa có cột tier snapshot trên bookings**; nếu cần lưu hạng phục vụ audit, Member 3/4 thống nhất migration mới trước khi triển khai. Không sửa V1.
- Với quote của Guest, Pricing chọn mức STANDARD/0 từ backend (có thể gọi `getMembershipBenefits(BigDecimal.ZERO)`); không nhận tier hoặc mức giảm do client khai báo.

### Validation và độ chính xác

Tiền dùng `BigDecimal`, phù hợp `NUMERIC(15,2)`: từ `0.00` đến `9999999999999.99`, tối đa 2 chữ số thập phân có nghĩa. Chấp nhận `10000.0000` và `1E+7`; từ chối `10000.001`, null, số âm, quá giới hạn, hoặc tổng vượt giới hạn. Không tự round input. Các số mũ cực lớn/nhỏ cũng bị từ chối mà không tạo lũy thừa lớn để chuẩn hóa.

Lỗi theo `ApiException` hiện có: HTTP 400, `INVALID_LOYALTY_AMOUNT` hoặc `INVALID_LOYALTY_TIER`. Không có endpoint Loyalty mới.

## Tích hợp cộng điểm về sau — Member 3/5 và Long

Đây là contract cho **M1-06**, chưa phải tính năng ghi điểm đã triển khai:

1. Booking/Payment xác nhận server-side booking đã được thanh toán và chuyển `COMPLETED`; không dựa vào thông báo thành công từ frontend. Không tạo sự kiện checkout riêng ngoài WBS.
2. Xác định `eligibleSpent` là số tiền hợp lệ thực trả sau giảm giá, loại trừ hoàn tiền và phần không hợp lệ. Booking hủy/chưa hoàn tất/chưa thanh toán không đủ điều kiện.
3. Trong cùng transaction, kiểm tra trạng thái, khóa profile để tránh mất cập nhật, insert `loyalty_awards`, cập nhật `eligible_spend_total`, `points_balance`, `tier_code`, `updated_at`. V1 có UNIQUE `loyalty_awards.booking_id` để chống ghi lặp.
4. Dùng `calculateEarnedPoints(eligibleSpent)` cho từng award, `calculateTierAfterSpending(oldTotal, eligibleSpent)` cho hạng mới; kiểm tra tổng tiền/điểm không vượt kiểu dữ liệu. Retry hoặc hai worker không được cộng lần hai.
5. Không gọi calculator để ghi điểm trong quote, hold hoặc callback thanh toán đơn thuần. Việc hoàn tiền sau khi đã award cần chính sách điều chỉnh được nhóm thống nhất riêng.

## M1-05 — endpoint đang tồn tại

| HTTP / endpoint | Guest | CUSTOMER | STAFF | ADMIN |
| --- | --- | --- | --- | --- |
| `GET /actuator/health` | Cho phép | Cho phép | Cho phép | Cho phép |
| `POST /api/v1/auth/register` | Cho phép | Cho phép | Cho phép | Cho phép |
| `POST /api/v1/auth/login` | Cho phép | Cho phép | Cho phép | Cho phép |
| `GET /api/v1/auth/me` | 401 | Tài khoản của mình | Tài khoản của mình | Tài khoản của mình |
| Route ngoài allowlist | 401 | 403 | 403 | 403 |

Request mang token sai/hết hạn hoặc tài khoản bị khóa trả 401, kể cả khi gửi token tới endpoint public. Public không có nghĩa bỏ qua lỗi bearer token đã gửi.

`AuthController.me` lấy ID từ JWT đã xác minh; `AuthService.me` kiểm tra lại ownership bằng method security. Tham số `userId` client tự thêm không chuyển sang tài khoản khác. JWT signature/claims, BCrypt, DTO register/login/me, CORS, stateless session và CSRF hiện có được giữ nguyên. Authorities lấy lại từ DB mỗi request; thay role/khóa tài khoản có hiệu lực với token cũ ở request tiếp theo.

Filter và MVC advice cùng giữ lỗi `{code, message, details}`: chưa xác thực = 401 `UNAUTHORIZED` + `WWW-Authenticate: Bearer`; đã xác thực nhưng bị từ chối = 403 `FORBIDDEN`. Không lộ exception/token và không biến lỗi method security thành 500.

### Policy dùng cho module tương lai

Bean `authorizationPolicy` không tự mở route. Nó nhận Authentication đã qua JWT decoder + DB converter hiện có.

| Policy | Quyền |
| --- | --- |
| `isCurrentUser(authentication, userId)` | JWT subject phải bằng user ID đích; mọi tài khoản hiện có chỉ xem chính mình |
| `isCustomerOwner(authentication, ownerUserId)` | CUSTOMER và sở hữu tài nguyên |
| `canReadBooking(authentication, ownerUserId)` | CUSTOMER sở hữu, hoặc STAFF/ADMIN đọc phục vụ nghiệp vụ |
| `canManageRooms(authentication)` | ADMIN |
| `canManageRoomBlocks(authentication)` | STAFF hoặc ADMIN |

`ownerUserId` phải đọc từ **tài nguyên đã lưu**. Ví dụ `bookings.customer_id` là `customer_profiles.user_id`, không phải booking ID và không phải ID client khai báo. Chủ sở hữu null/không hợp lệ bị từ chối kể cả ADMIN.

```java
@PreAuthorize("isAuthenticated() and @authorizationPolicy.canManageRooms(authentication)")
// Đặt trên public method của Spring service quản lý phòng khi module được tạo.

// Sau khi tải booking từ DB, kiểm tra policy với owner thật trước khi trả DTO.
// Các thao tác ghi của Customer cần isCustomerOwner; canReadBooking chỉ cấp quyền đọc.
```

Gọi method có annotation qua Spring bean/proxy; self-invocation trong cùng bean không tự kích hoạt `@PreAuthorize`. Prefix `isAuthenticated() and` giúp request thiếu SecurityContext bị từ chối đúng loại lỗi. Có thể dùng `#p0` cho đối số đầu tiên để không phụ thuộc tên tham số lúc biên dịch.

ADMIN không tự bỏ qua ownership của luồng Customer, trạng thái booking, khóa phòng hoặc kiểm tra trùng khoảng. Service RoomBlock vẫn phải kiểm tra `[startAt,endAt)`, confirmed bookings, quyền chặn/mở và khóa phòng trong transaction.

### Phần còn chờ để đóng M1-05

- Sang hoàn thiện M2-02 (CRUD loại phòng/phòng vật lý) và RoomBlock; Member 3/4/5 tạo API Booking, Pricing, Payment theo task của mình.
- Chốt **HTTP method + path thật** trong PR của từng module. Long bổ sung matcher cụ thể vào `SecurityConfig` trước `anyRequest().denyAll()`, đồng thời áp dụng policy lên service/tài nguyên đã lưu.
- Bổ sung integration tests HTTP cho Guest/Customer/Staff/Admin đúng endpoint vừa tạo, ownership booking và các điều kiện chặn/mở phòng. Hiện chỉ có policy tests cho các quyền nghiệp vụ chưa có controller.
- Catalog/search/quote public chỉ được mở khi API thật được triển khai và review. Không có route checkout, housekeeping, employee hay analytics trong phạm vi hiện tại.
- Không thêm Swagger/OpenAPI vì không có endpoint mới và repository chưa cài OpenAPI. Cập nhật tài liệu API khi module thực tế được thêm.

## Kiểm thử và vận hành

Trong `backend`, chạy `.\mvnw.cmd -B -ntp verify` (Linux/macOS: `./mvnw -B -ntp verify`). Các test dùng PostgreSQL 17 thật ở port ngẫu nhiên, áp dụng Flyway V1 rồi Hibernate `validate`; không dùng DB phát triển. Chỉ đặt `TEST_DB_URL`/`TEST_DB_USER`/`TEST_DB_PASSWORD` khi có database test riêng.

| Bộ test | Số case |
| --- | ---: |
| Auth/JWT regression hiện có | 22 |
| LoyaltyServiceTest | 36 |
| AuthorizationPolicyTest | 19 |
| RbacIntegrationTest (MockMvc, JWT thật, DB thật, method security) | 21 |
| SecurityErrorAdviceTest (401/403 qua MVC advice) | 2 |
| Tổng | **100** |

Kết quả ngày 16/10/2026: `verify` **BUILD SUCCESS**, 100 passed, 0 failures/errors/skipped. JAR mới khởi động thành công trên port 8080 với PostgreSQL local: Flyway validate V1, health `UP`; login và `/me` của CUSTOMER/STAFF/ADMIN trả 200; giả mạo query `userId` không đổi chủ tài khoản; thiếu token trả 401; route ngoài allowlist trả 403 với tài khoản đã xác thực. Frontend 5173 và API proxy vẫn hoạt động. Đây là kết quả local; chưa push nên chưa có CI cho các thay đổi mới.

Không xóa/sửa/vô hiệu hóa 22 test cũ. Không có test controller hoặc PolicyProbe trong production JAR. Trên Windows, dừng backend đang chạy trực tiếp file `target/*.jar` trước khi Maven repackage; có thể chạy bản sao JAR ở thư mục tạm để tránh khóa file build.

Tham khảo cách framework vận hành: [Spring method security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html), [JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
