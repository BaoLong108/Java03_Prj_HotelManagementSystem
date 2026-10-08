# Kiến trúc và quy tắc nghiệp vụ

## Ranh giới

- Một khách sạn. Khách chọn `RoomType`; backend tự chọn và gán một `PhysicalRoom` lúc tạo hold. Không lộ số phòng trong luồng tìm/đặt của khách.
- Guest chưa đăng nhập được xem/tìm phòng và lấy quote; để tạo hold/booking hoặc thanh toán phải đăng nhập thành Customer. Guest không có bản ghi user trong DB.
- Thời gian lưu bằng `startAt`, `endAt`; khoảng ở dạng nửa mở `[startAt, endAt)`. Mặc định nhận phòng 12:00, trả phòng 10:00 ngày cuối. Hai lượt kề nhau có 2 giờ xoay vòng.
- `RoomBlock` chặn một phòng vật lý theo khoảng thời gian, không dùng cờ `available` hiện tại để tính tương lai. Không cho block mới phủ booking đã xác nhận hoặc hold còn hiệu lực.
- Booking: `PENDING_PAYMENT` → `CONFIRMED` → `COMPLETED`; nhánh khác là `CANCELLED` hoặc `EXPIRED`. Hold giữ phòng 15 phút. Job hết hạn và hoàn tất phải an toàn khi chạy lặp.
- Khi giữ chỗ, một transaction khóa row phòng ứng viên, kiểm tra lại booking/hold/block chồng lấn, rồi tạo booking. Không dựa riêng vào kết quả tìm phòng trước đó.
- Quote và booking dùng tiền kiểu `BigDecimal`; lưu snapshot giá gốc, tỷ lệ giảm và số phải trả. Thay đổi tier/giá sau này không sửa booking cũ.
- Thanh toán demo là P0; VNPay Sandbox là P1. Return URL chỉ để hiển thị. Xác nhận thanh toán cần xử lý lặp an toàn.
- Loyalty: 1 điểm trên mỗi 10.000 VND thực trả sau giảm giá; chỉ cộng một lần khi booking đã thanh toán và `COMPLETED`. VIP từ 10 triệu VND giảm 10%, Diamond từ 30 triệu VND giảm 15%.

Không có staff check-in/check-out, folio, nhiều phòng trong một booking, hóa đơn điện tử hoặc cổng thanh toán khác trong phạm vi mini.

## Quy tắc code

- Backend chia package theo nghiệp vụ. Mỗi module tự quản entity, repository, service, controller và DTO của mình; `shared` chỉ chứa phần thật sự dùng chung.
- Schema do Flyway quản lý. Không dùng Hibernate `ddl-auto=update`; thêm migration mới khi đổi schema và không sửa migration đã áp dụng.
- Frontend chia `src/features/<module>`; mọi HTTP call đi qua `src/shared/api`. Không tự quyết availability, giá hoặc quyền truy cập ở client.
- Chủ sở hữu module thống nhất DTO và foreign key với module phụ thuộc trước khi mở PR. Dùng UTC/offset rõ ràng trong API, hiển thị theo múi giờ khách sạn ở giao diện.
