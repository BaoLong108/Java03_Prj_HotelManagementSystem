# NHẬT KÝ PHÁT TRIỂN MODULE BOOKING (DEV LOG)

> **Dự án**: Hotel PMS Mini (Hệ thống Quản lý Khách sạn Monorepo)  
> **Người thực hiện**: Phan Thanh Bình (GitHub: `thanhbinh1304` | Branch: `PhanThanhBinh`)  
> **Phạm vi phụ trách**: `backend/booking` (Backend) & `frontend/src/features/booking` (Frontend)  
> **Tài liệu tham chiếu**: [docs/ownership.md](ownership.md), [docs/architecture.md](architecture.md), [docs/database.md](database.md), [docs/api-contract.md](api-contract.md)

---

## 1. TỔNG QUAN & BỐI CẢNH DỰ ÁN

### 1.1 Vai trò của Module Booking trong Repository

Trong kiến trúc Modular Monolith của dự án Hotel PMS Mini:

- **Module Auth (`com.hotelpms.auth`)**: Quản lý tài khoản `app_users`, role và hồ sơ khách hàng `customer_profiles`.
- **Module Room (`com.hotelpms.room`)**: Quản lý danh mục loại phòng `room_types`, phòng vật lý `physical_rooms`, và các khoảng khóa phòng `room_blocks`.
- **Module Booking (`com.hotelpms.booking`) — _Khu vực phụ trách_**: Đóng vai trò hạt nhân trong luồng kinh doanh. Xử lý giữ chỗ phòng (Hold), kiểm tra trùng lặp lịch phòng, điều phối máy trạng thái của đơn đặt phòng (`PENDING_PAYMENT` $\rightarrow$ `CONFIRMED` $\rightarrow$ `COMPLETED` / `CANCELLED` / `EXPIRED`), tự động giải phóng phòng khi hết hạn hold.
- **Module Pricing (`com.hotelpms.pricing`)**: Tính giá gốc và áp dụng chiết khấu hạng thành viên, tạo snapshot giá bất biến khi tạo booking.
- **Module Payment (`com.hotelpms.payment`)**: Thanh toán thử nghiệm Demo và cổng VNPay Sandbox.
- **Module Loyalty (`com.hotelpms.loyalty`)**: Tích điểm và nâng hạng khách hàng khi booking hoàn tất.

### 1.2 Nguyên tắc nghiệp vụ bất di bất dịch cần tuân thủ

1. **Khách chọn loại phòng, backend gán phòng vật lý**: Khách hàng chỉ chọn `RoomType`, backend sẽ tự động chọn và gán một `PhysicalRoom` còn trống vào booking. Tuyệt đối không để lộ số phòng vật lý (`room_number`) cho khách trong suốt luồng tìm và đặt phòng.
2. **Khoảng thời gian nửa mở**: Tính theo `[startAt, endAt)` với mốc nhận phòng mặc định lúc 12:00 và trả phòng lúc 10:00 ngày cuối. Hai khoảng chạm đầu-cuối không bị tính là trùng lặp.
3. **Thời hạn giữ chỗ (Hold)**: Mặc định giữ phòng **15 phút** kể từ thời điểm tạo. Quá 15 phút chưa thanh toán thì hệ thống phải tự động hủy để khách khác có thể đặt.
4. **Không phụ thuộc vào Hibernate tự sinh bảng**: Toàn bộ schema do Flyway quản lý (`V1__initial_schema.sql`), cấu hình `spring.jpa.hibernate.ddl-auto=validate`. Mọi Entity phải ánh xạ khớp 100% tên bảng, kiểu dữ liệu, độ chính xác số học và ràng buộc toàn vẹn của PostgreSQL.

---

## 2. KẾ HOẠCH TỔNG THỂ CÁC SPRINT

### Sprint 1: Nền tảng Dữ liệu, Nghiệp vụ Giữ chỗ & Máy trạng thái (Backend)

| Giai đoạn       | Mã Task   | Trọng tâm                                                                          | Trạng thái     |
| :-------------- | :-------- | :--------------------------------------------------------------------------------- | :------------- |
| **Giai đoạn 1** | **M3-01** | Xây dựng Nền tảng Dữ liệu & Domain Model (Entity, Enum, Repository)                | **HOÀN THÀNH** |
| **Giai đoạn 2** | **M3-02** | Nghiệp vụ Giữ chỗ / Tạo Booking ban đầu (`PENDING_PAYMENT`) & Chống Race Condition | **HOÀN THÀNH** |
| **Giai đoạn 3** | **M3-03** | Vòng đời Trạng thái & Scheduled Job Quét hết hạn Hold (`hold-race`)                | **HOÀN THÀNH** |

### Sprint 2: Hoàn tất Vòng đời Tự động (M3-04), Kiểm thử Tranh chấp (M3-07) & Giao diện Booking (M3-05, M3-06)

| Giai đoạn       | Mã Task   | Trọng tâm                                                                                   | Trạng thái     |
| :-------------- | :-------- | :------------------------------------------------------------------------------------------ | :------------- |
| **Backend**     | **M3-04** | Scheduled Job tự động hoàn tất kỳ nghỉ (`CONFIRMED` $\rightarrow$ `COMPLETED` sau `endAt`)  | **HOÀN THÀNH** |
| **Backend**     | **M3-07** | Kiểm thử tải đồng thời tranh chấp phòng và hold hết hạn (Race conditions)                   | **HOÀN THÀNH** |
| **Frontend**    | **M3-05A**| Chuẩn hóa API Client & Type Definitions Frontend (`types.ts`, `api.ts`)                     | **HOÀN THÀNH** |
| **Frontend**    | **M3-05B**| Giao diện Tìm phòng, Chọn ngày & Xem Báo giá (Quote)                                        | **HOÀN THÀNH** |
| **Frontend**    | **M3-06** | Luồng Giữ chỗ (Hold 15 phút), Thanh toán Demo & Quản lý đơn cá nhân                         | **HOÀN THÀNH** |

---

## 3. CHI TIẾT TIẾN TRÌNH THỰC HIỆN

### 🟢 GIAI ĐOẠN 1 (Task M3-01): NỀN TẢNG DỮ LIỆU & DOMAIN MODEL

- **Thời gian thực hiện**: 15/10/2026 22:25 – 22:35 (GMT+7)
- **Trạng thái**: Hoàn thành 100% (Passed toàn bộ test tích hợp PostgreSQL).

#### Các Class & File đã tạo mới:

#### 1. `BookingStatus.java`

- **Vị trí**: `backend/src/main/java/com/hotelpms/booking/entity/BookingStatus.java`
- **Nhiệm vụ & Tác dụng**:
  - Định nghĩa Enum thể hiện 5 trạng thái vòng đời của một booking:
    - `PENDING_PAYMENT`: Đang giữ chỗ tạm thời (chờ khách thanh toán trong 15 phút).
    - `CONFIRMED`: Đã thanh toán thành công, phòng được bảo đảm giữ chắc chắn.
    - `COMPLETED`: Khách đã hoàn thành kỳ nghỉ và trả phòng; kích hoạt tích điểm Loyalty.
    - `CANCELLED`: Khách hoặc lễ tân chủ động hủy bỏ.
    - `EXPIRED`: Hết hạn 15 phút mà không thanh toán, phòng được giải phóng tự động.
  - Khớp chính xác với ràng buộc DB:
    ```sql
    CONSTRAINT ck_bookings_status CHECK (status IN (
        'PENDING_PAYMENT', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'EXPIRED'
    ))
    ```

#### 2. `Booking.java`

- **Vị trí**: `backend/src/main/java/com/hotelpms/booking/entity/Booking.java`
- **Nhiệm vụ & Tác dụng**:
  - Là thực thể JPA ánh xạ tới bảng `bookings` trong CSDL PostgreSQL.
  - Kế thừa [`AuditedEntity`](file:///e:/OJT-ki6/HotelManagementSystem/Java03_Prj_HotelManagementSystem/backend/src/main/java/com/hotelpms/shared/model/AuditedEntity.java) để tự động lưu mốc thời gian `created_at` và `updated_at`.
  - **Các trường dữ liệu quan trọng**:
    - `id`: Định danh khóa chính `BIGINT GENERATED BY DEFAULT AS IDENTITY`.
    - `bookingCode`: Mã đặt phòng duy nhất, phục vụ tra cứu công khai thay cho ID nội bộ.
    - `customerId`: Khóa ngoại trỏ sang `customer_profiles(user_id)`.
    - `roomTypeId`: Khóa ngoại trỏ sang loại phòng `room_types(id)`.
    - `physicalRoomId`: Khóa ngoại trỏ sang phòng vật lý `physical_rooms(id)`.
    - `guestCount`: Số lượng khách lưu trú (`SMALLINT > 0`).
    - `startAt`, `endAt`, `holdExpiresAt`: Các mốc thời gian kiểu `Instant` (`TIMESTAMPTZ` trong PostgreSQL).
    - `baseAmount`, `discountRate`, `discountAmount`, `payableAmount`: Sử dụng `BigDecimal` với `precision = 15, scale = 2` (và `scale = 4` cho tỷ lệ giảm) để đảm bảo không bị sai lệch số học dấu phẩy động của tiền tệ VND.
    - `confirmedAt`, `completedAt`, `cancelledAt`, `expiredAt`: Lưu vết thời điểm chuyển trạng thái.
  - **Phương thức nghiệp vụ tích hợp sẵn trong Entity**:
    - `confirm(Instant now)`: Chuyển từ `PENDING_PAYMENT` $\rightarrow$ `CONFIRMED`, ném ngoại lệ nếu trạng thái hiện tại không hợp lệ.
    - `complete(Instant now)`: Chuyển từ `CONFIRMED` $\rightarrow$ `COMPLETED`.
    - `cancel(Instant now)`: Hủy booking (chỉ cho phép khi đang chờ thanh toán hoặc đã xác nhận).
    - `expire(Instant now)`: Chuyển sang `EXPIRED` khi hết hạn giữ chỗ.
    - `isHoldActive(Instant now)`: Kiểm tra xem booking có còn đang giữ phòng hợp lệ hay không.

#### 3. `BookingRepository.java`

- **Vị trí**: `backend/src/main/java/com/hotelpms/booking/repository/BookingRepository.java`
- **Nhiệm vụ & Tác dụng**:
  - Giao diện kế thừa `JpaRepository<Booking, Long>` cung cấp các thao tác CRUD chuẩn.
  - **Các phương thức truy vấn mở rộng**:
    - `findByBookingCode(String code)`: Tra cứu booking theo mã (phục vụ khách hàng kiểm tra hoặc thanh toán).
    - `findByCustomerIdOrderByCreatedAtDesc(Long customerId)`: Xem lịch sử đặt phòng của khách hàng theo thứ tự mới nhất.
    - `findByStatusAndHoldExpiresAtBefore(BookingStatus status, Instant cutoff)`: Hỗ trợ tác vụ định kỳ quét và thu hồi các phòng giữ chỗ quá hạn 15 phút.
    - `hasActiveBookingOverlap(...)`: Truy vấn JPQL kiểm tra xem phòng vật lý đã có booking nào chiếm giữ trong khoảng `[startAt, endAt)` hay chưa.
    - `findOccupiedPhysicalRoomIds(...)`: Liệt kê tất cả ID phòng vật lý thuộc một loại phòng đang bận trong khoảng thời gian chỉ định, phục vụ cho thuật toán chọn phòng trống ở Giai đoạn 2.

#### 4. `BookingRepositoryTest.java`

- **Vị trí**: `backend/src/test/java/com/hotelpms/booking/BookingRepositoryTest.java`
- **Nhiệm vụ & Tác dụng**:
  - Khởi động Spring Boot Test tích hợp cùng PostgreSQL thật thông qua thư viện `EmbeddedPostgres`.
  - Xác nhận rằng Hibernate ở chế độ `ddl-auto: validate` kiểm tra cấu trúc bảng `bookings` thành công, không có bất kỳ xung đột kiểu dữ liệu nào với Flyway V1.
  - Kiểm thử 6 tình huống nghiệp vụ:
    1. Lưu và tìm kiếm booking theo `booking_code`.
    2. Chuyển đổi trạng thái qua các bước của vòng đời.
    3. Lọc danh sách hold hết hạn cần quét dọn.
    4. Kiểm tra logic trùng lặp ngày nhận/trả phòng.
    5. Phát hiện phòng vật lý đang bận theo loại phòng.
    6. Kiểm chứng ràng buộc ngoại lệ của PostgreSQL: Ràng buộc `ex_bookings_room_overlap` (sử dụng GiST index và tstzrange) từ chối 2 booking cùng chiếm 1 phòng tại thời điểm trùng nhau và ném ra lỗi `DataIntegrityViolationException`.

---

### 🟢 GIAI ĐOẠN 2 (Task M3-02): LOGIC GIỮ CHỖ, CHỐNG RACE CONDITION & API

- **Thời gian thực hiện**: 15/10/2026 23:00 – 23:05 (GMT+7)
- **Trạng thái**: Hoàn thành 100% (Passed toàn bộ 6/6 test tích hợp mới và 34/34 test toàn bộ backend).

#### Các Class & File đã tạo / chỉnh sửa:

#### 1. `CreateBookingRequest.java`

- **Vị trí**: `backend/src/main/java/com/hotelpms/booking/dto/CreateBookingRequest.java`
- **Nhiệm vụ & Tác dụng**: Record DTO nhận dữ liệu từ client để tạo giữ chỗ:
  - `roomTypeId`: ID loại phòng khách chọn (`@NotNull`).
  - `guestCount`: Số lượng khách (`@Positive short`).
  - `startAt`, `endAt`: Mốc thời gian nhận và trả phòng (`@NotNull Instant`).

#### 2. `BookingResponse.java`

- **Vị trí**: `backend/src/main/java/com/hotelpms/booking/dto/BookingResponse.java`
- **Nhiệm vụ & Tác dụng**: Record DTO phản hồi thông tin đơn đặt phòng cho client.
  - Chứa đầy đủ thông tin: `bookingCode`, `status`, `baseAmount`, `discountRate`, `discountAmount`, `payableAmount`, `holdExpiresAt`.
  - **Bảo mật nghiệp vụ**: Tuyệt đối **không chứa trường `physicalRoomId` hoặc số phòng vật lý** theo đúng nguyên tắc kiến trúc (khách chỉ chọn và biết loại phòng, việc phân bổ phòng do hệ thống tự điều phối ngầm).

#### 3. `RoomAllocationService.java`

- **Vị trí**: `backend/src/main/java/com/hotelpms/booking/service/RoomAllocationService.java`
- **Nhiệm vụ & Tác dụng**:
  - Điều phối và gán phòng vật lý an toàn trong transaction:
  - Lấy danh sách phòng vật lý ứng viên thuộc `roomTypeId` sắp xếp theo `id ASC` (`ORDER BY id ASC`).
  - Khóa hàng phòng vật lý bằng `SELECT ... FOR UPDATE` (Pessimistic Write Lock). Việc luôn khóa theo thứ tự ID tăng dần giúp **loại bỏ 100% nguy cơ Deadlock** giữa các transaction song song.
  - Kiểm tra đối chiếu kép: Đảm bảo phòng ứng viên không bị trùng lịch với bất kỳ Booking nào đang có hiệu lực (`hasActiveBookingOverlap`) VÀ không bị trùng với bất kỳ lịch khóa phòng bảo trì nào (`room_blocks`).

#### 4. `BookingService.java`

- **Vị trí**: `backend/src/main/java/com/hotelpms/booking/service/BookingService.java`
- **Nhiệm vụ & Tác dụng**:
  - Xác thực hồ sơ khách hàng `CustomerProfile` và tính hợp lệ của kỳ nghỉ (tối thiểu 1 đêm theo múi giờ `Asia/Ho_Chi_Minh`).
  - Kiểm tra loại phòng có đang hoạt động và số lượng khách có vượt quá `max_occupancy` hay không.
  - **Snapshot giá bất biến**: Tính `baseAmount = basePricePerNight * nights`. Đọc hạng thành viên của khách để tính chiết khấu (`STANDARD: 0%`, `VIP: 10%`, `DIAMOND: 15%`) và tính `payableAmount`.
  - Gán phòng vật lý và thiết lập thời gian giữ chỗ `holdExpiresAt = now + 15 minutes`.
  - Sinh mã `booking_code` chuẩn định dạng `BKyyMMdd-XXXXXX`.
  - Cung cấp hàm tra cứu đơn đặt phòng có phân quyền: Khách hàng chỉ xem được đơn của chính mình, nhân viên (`STAFF`) và quản trị viên (`ADMIN`) có thể xem mọi đơn.

#### 5. `BookingController.java`

- **Vị trí**: `backend/src/main/java/com/hotelpms/booking/controller/BookingController.java`
- **Nhiệm vụ & Tác dụng**: Expose các REST API endpoint:
  - `POST /api/v1/bookings`: Tạo giữ phòng (yêu cầu đăng nhập tài khoản).
  - `GET /api/v1/bookings/{bookingCode}`: Xem chi tiết đơn đặt phòng.
  - `GET /api/v1/bookings`: Xem danh sách các đơn đặt phòng của chính khách hàng hiện tại.

#### 6. Cập nhật `SecurityConfig.java` & `ApiExceptionHandler.java`

- **`SecurityConfig.java`**: Khai báo phân quyền cho phép các request có Bearer token hợp lệ truy cập các endpoint `/api/v1/bookings/**`.
- **`ApiExceptionHandler.java`**: Bắt lỗi vi phạm ràng buộc ngoại lệ của PostgreSQL `ex_bookings_room_overlap` và tự động ánh xạ thành HTTP `409 CONFLICT` với mã lỗi chuẩn `ROOM_NOT_AVAILABLE` thay vì lỗi 500 máy chủ.

#### 7. `BookingIntegrationTest.java`

- **Vị trí**: `backend/src/test/java/com/hotelpms/booking/BookingIntegrationTest.java`
- **Nhiệm vụ & Tác dụng**: Bộ kiểm thử tích hợp toàn diện gồm 6 kịch bản thực tế:
  1. Tạo booking thành công, kiểm tra tính toán tiền, thời hạn hold 15 phút, ẩn số phòng, tra cứu theo mã và theo danh sách.
  2. Tính toán chiết khấu tự động cho khách hàng hạng `VIP` (giảm 10%).
  3. Báo lỗi `409 ROOM_NOT_AVAILABLE` khi tất cả các phòng của loại phòng đã kín lịch.
  4. Báo lỗi `409 ROOM_NOT_AVAILABLE` khi phòng vật lý còn lại bị khóa bởi lịch b ảo trì (`room_blocks`).
  5. Phân quyền: Khách hàng khác không thể xem đơn của người khác (HTTP 403), nhưng Staff xem được (HTTP 200).
  6. **Kiểm thử tải đồng thời (Race Condition)**: Khi chỉ còn duy nhất 1 phòng trống, gửi đồng thời 2 request đặt phòng qua `CompletableFuture`. Kết quả chứng minh: Đúng 1 request thành công (HTTP 201) và request còn lại nhận HTTP 409 `ROOM_NOT_AVAILABLE`, trong DB chỉ có duy nhất 1 bản ghi được tạo ra.

---

### 🟢 GIAI ĐOẠN 3 (Task M3-03): MÁY TRẠNG THÁI & JOB QUÉT HẾT HẠN HOLD

- **Thời gian thực hiện**: 16/10/2026 09:35 – 09:50 (GMT+7)
- **Trạng thái**: Hoàn thành 100% (Passed toàn bộ 12/12 test booking và 40/40 test toàn hệ thống).

#### Các Class & File đã tạo / chỉnh sửa:

#### 1. Cập nhật `BookingRepository.java` (Khóa dòng PESSIMISTIC_WRITE)

- **Vị trí**: `backend/src/main/java/com/hotelpms/booking/repository/BookingRepository.java`
- **Nhiệm vụ & Tác dụng**:
  - Bổ sung 2 phương thức khóa dòng:
    - `findByBookingCodeForUpdate(String bookingCode)`: Khóa hàng đơn đặt phòng bằng `SELECT ... FOR UPDATE` khi thực hiện chuyển trạng thái (Hủy, Xác nhận, Hoàn tất).
    - `findByIdForUpdate(Long id)`: Khóa hàng đơn đặt phòng khi Job chạy quét hết hạn.
  - **Ý nghĩa kỹ thuật**: Giúp tuần tự hóa (serialize) các thao tác cạnh tranh giữa luồng thanh toán gọi về và tiến trình nền quét hết hạn, giải quyết triệt để vấn đề `hold-race`.

#### 2. Cập nhật `BookingService.java` (Logic Máy trạng thái & Quét dọn)

- **Vị trí**: `backend/src/main/java/com/hotelpms/booking/service/BookingService.java`
- **Nhiệm vụ & Tác dụng**:
  - `cancelBooking(bookingCode, callerUserId, isStaffOrAdmin)`:
    - Cho phép khách hàng chủ động hủy đơn giữ chỗ của mình khi chưa thanh toán (`PENDING_PAYMENT`).
    - Kiểm tra phân quyền chặt chẽ; từ chối hủy nếu đơn đã `EXPIRED` hoặc `COMPLETED`. Tính lũy thừa (Idempotent) nếu gọi hủy nhiều lần.
  - `confirmBooking(bookingCode)`:
    - Xác nhận đơn đặt phòng khi nhận thông báo thanh toán thành công, chuyển sang `CONFIRMED` và ghi nhận `confirmedAt`.
    - Kiểm tra tính hợp lệ thời gian thực: Nếu thời điểm thanh toán đến muộn hơn `holdExpiresAt` (quá 15 phút), đơn sẽ tự động chuyển sang `EXPIRED`, lưu vào DB và trả về lỗi HTTP 409 `BOOKING_EXPIRED` để ngăn chặn thanh toán cho đơn giữ chỗ đã chết.
    - Cấu hình `@Transactional(noRollbackFor = ApiException.class)` đảm bảo việc cập nhật `EXPIRED` vẫn được lưu vào DB ngay cả khi ném ra ngoại lệ 409.
  - `completeBooking(bookingCode, callerUserId, isStaffOrAdmin)`:
    - Hoàn tất kỳ nghỉ khi khách trả phòng, chuyển trạng thái sang `COMPLETED` và ghi nhận `completedAt`. Chỉ dành cho tài khoản nhân viên (`STAFF`) hoặc quản trị viên (`ADMIN`).
  - `sweepExpiredHolds(Instant now)` & `expireHoldIfPending(bookingId, now)`:
    - Quét tất cả các booking đang `PENDING_PAYMENT` có `holdExpiresAt <= now`.
    - Khóa dòng từng booking và chuyển sang `EXPIRED`, giải phóng phòng vật lý ngay lập tức để phục vụ khách hàng khác.

#### 3. `HoldExpirationScheduler.java` & `BookingSchedulingConfig.java`

- **Vị trí**:
  - `backend/src/main/java/com/hotelpms/booking/service/HoldExpirationScheduler.java`
  - `backend/src/main/java/com/hotelpms/booking/config/BookingSchedulingConfig.java`
- **Nhiệm vụ & Tác dụng**:
  - Kích hoạt cơ chế lập lịch `@EnableScheduling` với cờ cấu hình linh hoạt `app.booking.scheduler.enabled`.
  - Tác vụ định kỳ `@Scheduled` chạy mỗi 60 giây (`fixedDelayString = "${app.booking.expiration-sweep-interval-ms:60000}"`) tự động gọi hàm `sweepExpiredHolds()` để dọn dẹp các đơn giữ phòng quá hạn.

#### 4. Cập nhật `BookingController.java`

- **Vị trí**: `backend/src/main/java/com/hotelpms/booking/controller/BookingController.java`
- **Nhiệm vụ & Tác dụng**: Mở rộng các REST API endpoint:
  - `POST /api/v1/bookings/{bookingCode}/cancel`: Khách hàng/Staff hủy đơn.
  - `POST /api/v1/bookings/{bookingCode}/confirm`: Xác nhận thanh toán thành công.
  - `POST /api/v1/bookings/{bookingCode}/complete`: Staff/Admin đánh dấu hoàn tất kỳ nghỉ.

#### 5. Mở rộng `BookingIntegrationTest.java` (Thêm 6 bài kiểm thử mới)

- **Vị trí**: `backend/src/test/java/com/hotelpms/booking/BookingIntegrationTest.java`
- **Nhiệm vụ & Tác dụng**: Bổ sung kiểm thử sâu cho Task M3-03:
  1. Hủy booking thành công và tính lũy thừa khi gọi nhiều lần.
  2. Xác nhận booking thành công và tính lũy thừa khi gọi nhiều lần.
  3. Từ chối xác nhận (HTTP 409 `BOOKING_EXPIRED`) khi hold quá 15 phút, tự động đổi DB sang `EXPIRED`.
  4. Phân quyền hoàn tất booking: Khách hàng bị chặn (HTTP 403), Staff được phép (HTTP 200).
  5. Quét dọn hold tự động: Sau khi chạy quét hết hạn, phòng bị chiếm được giải phóng cho khách mới đặt thành công (HTTP 201).
  6. **Kiểm thử chạy đua `hold-race`**: Cho 2 luồng chạy đồng thời tại đúng thời điểm hết hạn hold (1 luồng gọi confirm, 1 luồng gọi quét dọn). Kết quả: Dữ liệu luôn đồng nhất tuyệt đối (hoặc là `CONFIRMED` hoặc là `EXPIRED`), không có bất kỳ trạng thái trung gian hay lỗi hệ thống nào.

---

### 🟢 GIAI ĐOẠN 4 (Task M3-04): TỰ ĐỘNG HOÀN TẤT KỲ NGHỈ (AUTO-COMPLETE DEPARTED BOOKINGS)

- **Thời gian thực hiện**: 22/10/2026 18:00 – 18:05 (GMT+7)
- **Trạng thái**: Hoàn thành 100% (Passed toàn bộ 20/20 test booking và 42/42 test toàn bộ backend).

#### Bối cảnh & Tiêu chí nghiệm thu:
- Khách hàng đã thanh toán thành công (`CONFIRMED`). Khi kỳ nghỉ kết thúc (qua mốc trả phòng `endAt <= now`), hệ thống cần tự động đánh dấu trạng thái sang `COMPLETED` mà không bắt buộc nhân viên lễ tân phải bấm nút thủ công.
- **Tiêu chí WBS**: *"Booking đã trả tiền tự COMPLETED sau endAt, mỗi lần đúng một lần (Idempotency)."*

#### Các Class & File đã tạo / chỉnh sửa:

#### 1. Cập nhật `BookingRepository.java`
- Bổ sung truy vấn:
  ```java
  List<Booking> findByStatusAndEndAtLessThanEqual(BookingStatus status, Instant cutoff);
  ```
- Cho phép tìm kiếm chính xác các đơn `CONFIRMED` có mốc `end_at` nhỏ hơn hoặc bằng thời điểm quét.

#### 2. Cập nhật `BookingService.java`
- Bổ sung phương thức điều phối: `autoCompleteDepartedBookings(Instant now)`.
- Bổ sung phương thức xử lý an toàn: `completeBookingIfConfirmed(Long bookingId, Instant now)`:
  - Khóa hàng bi quan `findByIdForUpdate(bookingId)`.
  - Kiểm tra điều kiện kép: đơn phải đang ở trạng thái `CONFIRMED` VÀ `endAt <= now`.
  - Chuyển trạng thái sang `COMPLETED`, lưu `completedAt = now`.
  - **Tính lũy thừa (Idempotent)**: Chạy lặp lại nhiều lần không sinh ra lỗi, không hoàn tất lần 2, bỏ qua các đơn đã hủy hoặc chưa thanh toán.

#### 3. Cập nhật `HoldExpirationScheduler.java`
- Bổ sung tác vụ định kỳ:
  ```java
  @Scheduled(fixedDelayString = "${app.booking.completion-sweep-interval-ms:60000}")
  public void sweepDepartedBookings() { ... }
  ```
- Tự động chạy nền mỗi phút để quét và chuyển đổi trạng thái hoàn tất kỳ nghỉ cho khách hàng.

#### 4. Bổ sung Kiểm thử Tích hợp
- `BookingRepositoryTest.java`: Thêm ca kiểm thử `testFindByStatusAndEndAtLessThanEqual` xác thực truy vấn lọc đúng đơn đã trả phòng và bỏ qua đơn tương lai hoặc đơn chưa thanh toán.
- `BookingIntegrationTest.java`: Thêm ca kiểm thử `testAutoCompleteDepartedBookings_CompletesConfirmedPastEndAt`:
  - Tạo và xác nhận đơn (`CONFIRMED`).
  - Cập nhật thời điểm kết thúc về quá khứ.
  - Chạy quét `autoCompleteDepartedBookings`: Chuyển sang `COMPLETED` và gán `completedAt`.
  - Chạy lại lần thứ 2: Trả về 0 và dữ liệu giữ nguyên tính toàn vẹn (Idempotency check PASS).

---

## SPRINT 2: GIAO DIỆN TÌM PHÒNG, GIỮ CHỖ & THANH TOÁN DEMO (TASKS M3-05 & M3-06)

### 🟢 GIAI ĐOẠN 1 (Task M3-05A): Chuẩn hóa API Client & Type Definitions Frontend

- **Thời gian thực hiện**: 16/10/2026 11:05 – 11:20 (GMT+7)
- **Trạng thái**: Hoàn thành 100% (`npm run build` PASS, 0 lỗi TypeScript).

#### Các File đã tạo mới:

#### 1. `types.ts`

- **Vị trí**: `frontend/src/features/booking/types.ts`
- **Nhiệm vụ & Tác dụng**:
  - `BookingStatus`: Định nghĩa Union type 5 trạng thái (`PENDING_PAYMENT`, `CONFIRMED`, `COMPLETED`, `CANCELLED`, `EXPIRED`).
  - `BookingResponse`: Khớp chính xác DTO từ backend `BookingController`. **Tuyệt đối không có trường `physicalRoomId` hay số phòng vật lý**, tuân thủ nguyên tắc an toàn thông tin nghiệp vụ.
  - `CreateBookingRequest`: Payload gửi lên endpoint `POST /api/v1/bookings` gồm `roomTypeId`, `guestCount`, `startAt`, `endAt`.
  - `RoomType`: Model loại phòng (mã, tên, mô tả, sức chứa tối đa, giá cơ sở).
  - `PriceQuote`: DTO tính toán báo giá chi tiết (số đêm, giá cơ sở, tỷ lệ chiết khấu, số tiền giảm, tổng phải trả).
  - `DEFAULT_ROOM_TYPES`: Bộ dữ liệu loại phòng dự phòng (Standard City View, Deluxe Ocean View, Presidential Suite) chuẩn theo Flyway V1 schema.
  - Tiện ích định dạng: `formatVnd()` (chuẩn tiền tệ Việt Nam Đồng), `formatDate()` và `formatDateTime()` (theo múi giờ `Asia/Ho_Chi_Minh`).
  - Bảng ánh xạ nhãn hiển thị và tone màu Badge trực quan (`bookingStatusLabels`, `bookingStatusTones`).

#### 2. `api.ts`

- **Vị trí**: `frontend/src/features/booking/api.ts`
- **Nhiệm vụ & Tác dụng**:
  - Cung cấp đối tượng `bookingApi` tích hợp chặt chẽ với `apiRequest` từ `shared/api/client`:
    - `bookingApi.create(body)`: Gửi request tạo giữ chỗ (`POST /api/v1/bookings`).
    - `bookingApi.getByCode(bookingCode)`: Tra cứu đơn theo mã (`GET /api/v1/bookings/{bookingCode}`).
    - `bookingApi.getMyBookings()`: Lấy danh sách lịch sử đặt phòng của khách hàng (`GET /api/v1/bookings`).
    - `bookingApi.cancel(bookingCode)`: Hủy đơn đặt phòng (`POST /api/v1/bookings/{bookingCode}/cancel`).
    - `bookingApi.confirm(bookingCode)`: Xác nhận thanh toán giữ chỗ (`POST /api/v1/bookings/{bookingCode}/confirm`).
    - `bookingApi.complete(bookingCode)`: Đánh dấu hoàn tất kỳ nghỉ (`POST /api/v1/bookings/{bookingCode}/complete`).
    - `bookingApi.getRoomTypes()`: Khả năng tự phục hồi (resilient) - gọi `/api/v1/room-types` nếu có, tự động fallback về `DEFAULT_ROOM_TYPES` nếu endpoint backend chưa sẵn sàng.
  - Cung cấp hàm `calculateQuote()`: Tính toán tức thì số đêm lưu trú, áp dụng chiết khấu hạng thành viên (`STANDARD`: 0%, `VIP`: 10%, `DIAMOND`: 15%) để hiển thị báo giá trực quan mà không cần chờ gọi backend.

---

### 🟢 GIAI ĐOẠN 2 (Task M3-05B): GIAO DIỆN TÌM PHÒNG, CHỌN NGÀY & XEM BÁO GIÁ (QUOTE)
* **Thời gian thực hiện**: 21/10/2026 08:45 – 08:55 (GMT+7)
* **Trạng thái**: Hoàn thành 100% (Kiểm thử thực tế trên trình duyệt bằng Browser Subagent, `npm run build` PASS, 0 lỗi TypeScript).

#### Các thành phần và tính năng đã triển khai:

#### 1. Nâng cấp `BookingPage.tsx`
* **Vị trí**: `frontend/src/features/booking/BookingPage.tsx`
* **Nhiệm vụ & Tác dụng**:
  - **Form chọn ngày lưu trú**:
    - Nhận phòng từ 12:00 trưa, trả phòng trước 10:00 sáng ngày kết thúc theo chuẩn múi giờ `Asia/Ho_Chi_Minh`.
    - Tự động điều chỉnh `checkOutDate` nếu người dùng chọn ngày nhận phòng vượt quá ngày trả phòng hiện tại.
    - Giới hạn ngày nhận phòng không được nhỏ hơn ngày hôm nay (`min={getTodayDateString()}`).
  - **Bộ chọn số khách & Validate sức chứa**:
    - Tự động đối chiếu số lượng khách với `maxOccupancy` của loại phòng được chọn.
    - Hiển thị Alert cảnh báo nguy cơ nếu số khách vượt quá sức chứa tối đa và vô hiệu hóa nút đặt phòng để bảo đảm tính toàn vẹn nghiệp vụ.
  - **Danh mục thẻ phòng trực quan (Room Cards)**:
    - Render danh sách các loại phòng (Standard City View, Deluxe Ocean View, Presidential Suite) từ `bookingApi.getRoomTypes()` hoặc bộ dữ liệu mẫu dự phòng.
    - Thẻ phòng hỗ trợ chọn với hiệu ứng chuyển đổi mượt mà, khung viền thương hiệu `--primary`, radio indicator, huy hiệu sức chứa và trạng thái phòng.
  - **Bảng báo giá tức thì (Real-time Quote Summary)**:
    - Cột cố định (Sticky) hiển thị thông tin loại phòng, thời gian lưu trú, số đêm, giá cơ sở mỗi đêm.
    - Công cụ xem trước mức chiết khấu thành viên (`STANDARD`: 0%, `VIP`: 10%, `DIAMOND`: 15%) tính toán tức thì bằng `calculateQuote()` mà không cần gọi API gây lag giao diện.
    - Hiển thị thông báo tiết kiệm chi phí thông minh và tổng tiền tạm tính rõ ràng.

#### 2. Đồng bộ phong cách giao diện trong `styles.css`
* **Vị trí**: `frontend/src/styles.css`
* **Nhiệm vụ & Tác dụng**:
  - Bổ sung hệ thống CSS classes chuyên biệt: `.booking-container`, `.booking-filter-card`, `.filter-form-grid`, `.booking-main-grid`, `.room-card`, `.quote-card`, `.quote-total`, `.radio-circle`.
  - Đồng bộ hoàn toàn với palette màu đặc trưng của dự án: Deep Teal (`--primary: #176e65`), Ink (`#1e343d`), Border (`#e6ebed`), cùng typography Segoe UI.
  - Hỗ trợ Responsive đa điểm ngắt: màn hình lớn 2 cột cân đối, màn hình trung bình (dưới 1180px), tablet (dưới 900px), và giao diện điện thoại (dưới 620px).

#### 3. Bổ sung tiện ích ngày giờ trong `types.ts`
* **Vị trí**: `frontend/src/features/booking/types.ts`
* **Nhiệm vụ & Tác dụng**:
  - `getTodayDateString()`: Lấy ngày hôm nay định dạng `YYYY-MM-DD` theo múi giờ Việt Nam.
  - `getTomorrowDateString()`: Lấy ngày tiếp theo chuẩn xác.
  - `toCheckInIso(dateStr)` & `toCheckOutIso(dateStr)`: Chuẩn hóa mốc thời gian ISO-8601 `[YYYY-MM-DDT12:00:00+07:00, YYYY-MM-DDT10:00:00+07:00)` sẵn sàng gửi lên Backend ở Giai đoạn 3.

---

### 🟢 GIAI ĐOẠN 3 (Task M3-06): LUỒNG GIỮ CHỖ (HOLD 15 PHÚT), THANH TOÁN DEMO & QUẢN LÝ ĐƠN CÁ NHÂN
* **Thời gian thực hiện**: 21/10/2026 09:15 – 09:25 (GMT+7)
* **Trạng thái**: Hoàn thành 100% (Kiểm thử thực tế giao diện và các luồng tương tác trên trình duyệt, `npm run build` PASS, 0 lỗi TypeScript).

#### Các thành phần và tính năng đã triển khai:

#### 1. Nâng cấp `BookingPage.tsx` thành luồng 3 bước liền mạch
* **Vị trí**: `frontend/src/features/booking/BookingPage.tsx`
* **Nhiệm vụ & Tác dụng**:
  - **Hệ thống Tab phân tầng**:
    - Tab 1: **"Đặt phòng mới"** (Luồng tìm phòng, giữ chỗ và thanh toán).
    - Tab 2: **"Đơn đặt phòng của tôi"** (kèm huy hiệu đếm tổng số đơn của khách hàng).
  - **Bước 1: Chọn phòng & Báo giá (`SEARCH`)**:
    - Hoàn tất chọn phòng, ngày nhận/trả và xem chi tiết bảng báo giá.
    - Nhấn nút "Tiến hành giữ phòng (Hold 15p)" $\rightarrow$ Gửi API `POST /api/v1/bookings` để tạo đơn trạng thái `PENDING_PAYMENT`.
  - **Bước 2: Giữ phòng & Đồng hồ đếm ngược 15:00 (`HOLD`)**:
    - Hiển thị mã đơn công khai `bookingCode`.
    - **Đồng hồ đếm ngược thời gian thực (`MM:SS`)**:
      - Tự động trừ lùi mỗi giây dựa trên mốc `holdExpiresAt`.
      - Thanh tiến trình trực quan (`hold-progress-bar`) co dần theo thời gian.
      - Chuyển màu cảnh báo đỏ nhấp nháy (`urgent`) khi còn dưới 3 phút để nhắc khách hoàn tất thanh toán.
      - Tự động chuyển trạng thái đơn sang `EXPIRED` khi hết 15 phút, giải phóng phòng và hiển thị nút "Đặt phòng mới".
    - Hộp tóm tắt chi tiết đơn: Thời gian nhận/trả, số khách, tiền gốc, tiền giảm giá, và tổng phải trả.
    - Thao tác:
      - Nút **"Thanh toán Demo ngay"**: Gọi `bookingApi.confirm(code)` $\rightarrow$ Chuyển sang Bước 3 (`CONFIRMED`).
      - Nút **"Hủy giữ chỗ"**: Gọi `bookingApi.cancel(code)` $\rightarrow$ Hủy đơn an toàn.
  - **Bước 3: Biên lai xác nhận thành công (`CONFIRMED`)**:
    - Hiển thị màn hình chúc mừng với icon xanh lá, mã đặt phòng, biên lai thanh toán chi tiết.
    - Nút "Xem đơn đặt phòng của tôi" hoặc "Đặt thêm phòng khác".

#### 2. Mục Quản lý đơn cá nhân ("Đơn đặt phòng của tôi")
* **Nhiệm vụ & Tác dụng**:
  - Tự động gọi `bookingApi.getMyBookings()` để lấy danh sách toàn bộ các booking của khách hàng đang đăng nhập.
  - Hiển thị bảng chi tiết: Mã đơn, tên loại phòng, thời gian lưu trú, số khách, tổng tiền, huy hiệu trạng thái sống động (`bookingStatusTones`).
  - **Hành động tức thì**:
    - Nếu đơn đang `PENDING_PAYMENT` (còn hạn giữ chỗ): Cung cấp nút **"Thanh toán"** (nạp lại đơn vào Bước 2 để hoàn tất) và nút **"Hủy"** (hủy đơn ngay trên bảng).
    - Nếu đơn đã xác nhận `CONFIRMED`: Huy hiệu trạng thái "Sẵn sàng nhận phòng".
    - Nếu đơn đã hủy hoặc hết hạn: Nút "Đặt lại" để nhanh chóng tạo booking mới.
  - Hỗ trợ trạng thái rỗng (EmptyState) khi chưa đăng nhập hoặc khi chưa có đơn nào.

#### 3. Bổ sung styles trong `styles.css`
* **Vị trí**: `frontend/src/styles.css`
* **Nhiệm vụ & Tác dụng**:
  - Thêm classes: `.booking-tabs`, `.booking-tab-btn`, `.hold-card`, `.hold-timer-box`, `.hold-timer-digits`, `.hold-progress-bar`, `.booking-details-box`, `.booking-success-card`.
  - Hiệu ứng nhấp nháy (`@keyframes pulse`) cho đồng hồ đếm ngược khi thời gian còn dưới 3 phút.

---

## 4. LỊCH SỬ CÁC LẦN CẬP NHẬT (CHANGELOG)

- **2026-10-15 22:58**:
  - Khởi tạo file log.
  - Hoàn thành Giai đoạn 1 (Task M3-01): Tạo `BookingStatus.java`, `Booking.java`, `BookingRepository.java`, và `BookingRepositoryTest.java`.
  - Kiểm tra xác minh `.\mvnw.cmd verify`: BUILD SUCCESS (Toàn bộ 6 test booking + các test auth chạy đạt 100%).
  - Commit thành công trên nhánh `PhanThanhBinh`: `843f303 Complete M3-01 booking entity, status enum and repository foundation`.
- **2026-10-15 23:05**:
  - Hoàn thành Giai đoạn 2 (Task M3-02): Tạo `CreateBookingRequest.java`, `BookingResponse.java`, `RoomAllocationService.java`, `BookingService.java`, `BookingController.java`, và `BookingIntegrationTest.java`.
  - Cập nhật cấu hình bảo mật `SecurityConfig.java` (`/api/v1/bookings/**`) và xử lý lỗi `ApiExceptionHandler.java` (bắt `ex_bookings_room_overlap`).
  - Chạy `.\mvnw.cmd verify`: BUILD SUCCESS (Toàn bộ 34/34 test passed 100%).
  - Commit thành công trên nhánh `PhanThanhBinh`: `9c09e5f Complete M3-02 booking creation service, concurrency locking and API endpoints`.
- **2026-10-16 09:50**:
  - Hoàn thành Giai đoạn 3 (Task M3-03): Triển khai máy trạng thái (`cancelBooking`, `confirmBooking`, `completeBooking`), khóa hàng PESSIMISTIC_WRITE chống `hold-race`, cơ chế quét hết hạn `sweepExpiredHolds`, và Scheduled Job `HoldExpirationScheduler`.
  - Thêm 6 ca kiểm thử tích hợp chuyên sâu cho máy trạng thái và tải đồng thời.
  - Chạy `.\mvnw.cmd verify`: BUILD SUCCESS (Toàn bộ 40/40 test passed 100%).
  - Commit thành công trên nhánh `PhanThanhBinh`: `4d64567 Complete M3-03 booking lifecycle state machine, hold expiration scheduler and race condition prevention`.
- **2026-10-16 11:20**:
  - Hoàn thành Giai đoạn 1 của Sprint 2 (Task M3-05A): Xây dựng nền tảng Types & API Client cho Frontend Booking.
  - Tạo `frontend/src/features/booking/types.ts` và `frontend/src/features/booking/api.ts`.
  - Tích hợp tính toán báo giá theo hạng hội viên (`calculateQuote`), định dạng tiền tệ VND (`formatVnd`), định dạng ngày giờ Việt Nam.
  - Xác nhận biên dịch Frontend qua `npm run build`: Build thành công 100% không có lỗi TypeScript hay cú pháp.
  - Commit thành công trên nhánh `PhanThanhBinh`: `45e0642 Complete M3-05A booking frontend types and api client foundation`.
- **2026-10-21 08:55**:
  - Hoàn thành Giai đoạn 2 của Sprint 2 (Task M3-05B): Xây dựng giao diện tìm kiếm phòng, chọn ngày lưu trú và bảng báo giá tức thì.
  - Nâng cấp `BookingPage.tsx` và bổ sung hệ thống CSS phong cách Teal/Ink chuẩn thiết kế dự án trong `styles.css`.
  - Bổ sung các tiện ích chuẩn hóa mốc thời gian nhận/trả phòng `[12:00, 10:00)` trong `types.ts`.
  - Kiểm thử trực quan tự động bằng Browser Subagent: tương tác mượt mà giữa chọn loại phòng, thay đổi ngày lưu trú và tính toán chiết khấu hội viên tức thì.
  - Xác nhận biên dịch Frontend `npm run build`: BUILD SUCCESS (0 lỗi TypeScript).
  - Commit thành công trên nhánh `PhanThanhBinh`: `317adb7 Complete M3-05B availability search and real-time quote UI`.
- **2026-10-21 09:25**:
  - Hoàn thành Giai đoạn 3 của Sprint 2 (Task M3-06): Luồng giữ phòng 15 phút, đồng hồ đếm ngược thời gian thực, thanh toán Demo và bảng quản lý đơn đặt phòng cá nhân.
  - Xây dựng hoàn chỉnh luồng 3 bước trong `BookingPage.tsx` và bổ sung các components tương tác trong `styles.css`.
  - Kiểm thử trực quan qua trình duyệt: chuyển đổi mượt mà giữa tab Đặt phòng mới và Đơn đặt phòng của tôi.
  - Xác nhận biên dịch Frontend `npm run build`: BUILD SUCCESS (0 lỗi TypeScript).
  - Commit thành công trên nhánh `PhanThanhBinh`: `47dcad1 Complete M3-06 hold countdown, payment demo flow and my bookings management`.
- **2026-10-22 18:10**:
  - Hoàn thành nốt phạm vi Task M3-04: Xây dựng Scheduled Job tự động hoàn tất kỳ nghỉ (`autoCompleteDepartedBookings`) cho các đơn `CONFIRMED` đã qua thời điểm `endAt`.
  - Cập nhật `BookingRepository.java` (`findByStatusAndEndAtLessThanEqual`), `BookingService.java` (`autoCompleteDepartedBookings`, `completeBookingIfConfirmed` với khóa bi quan và tính lũy thừa), `HoldExpirationScheduler.java` (`sweepDepartedBookings`).
  - Mở rộng bộ kiểm thử tích hợp: thêm `testFindByStatusAndEndAtLessThanEqual` trong `BookingRepositoryTest.java` và `testAutoCompleteDepartedBookings_CompletesConfirmedPastEndAt` trong `BookingIntegrationTest.java`.
  - Chạy `.\mvnw.cmd test -Dtest=Booking*`: 20/20 test booking PASS 100%.
  - Chạy toàn bộ backend test: 42/42 test PASS 100%, 0 failed, 0 errors, 0 skipped.
  - Chính thức đạt **100% hoàn thành toàn bộ các task của Sprint 1 & Sprint 2** (M3-01, M3-02, M3-03, M3-04, M3-07, M3-05A, M3-05B và làm trước M3-06).
