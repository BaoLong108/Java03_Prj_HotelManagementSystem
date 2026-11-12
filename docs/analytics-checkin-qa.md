# Revenue analytics và check-in online

## Audit

- Baseline `776cf10` trên `BaoLong` với Floor Map và Rate Calendar đang ở working tree.
- Java 21, Spring Boot 4.1.1, PostgreSQL/Flyway và React/TypeScript. JWT lấy vai trò từ DB.
- Payment demo lưu số tiền tại thời điểm thanh toán. DB giới hạn một giao dịch thành công cho mỗi booking. PDF xác nhận đọc số tiền snapshot.
- Booking có các mốc tạo, xác nhận, hết hạn, hủy và hoàn tất. Scheduler hoàn tất booking đã thanh toán khi đến giờ kết thúc. Chưa có sự kiện nhận/trả phòng thực tế, lịch sử trạng thái phòng hoặc hoàn tiền.
- Thực hiện trên working tree hiện tại. Chỉ dùng bản ghi QA riêng khi kiểm thử ghi dữ liệu.

## Analytics

Admin mở `/admin/analytics`. Các API `GET /api/v1/admin/analytics`, `GET /api/v1/admin/analytics/room-types` và `GET /api/v1/admin/analytics/export.csv` yêu cầu ADMIN. Báo cáo và CSV dùng `from`, `to` (bao gồm hai ngày) và `roomTypeId` tùy chọn, tối đa 366 ngày theo giờ Việt Nam.

| Chỉ số | Cơ sở tính |
| --- | --- |
| Đơn đặt phòng và phân bố trạng thái | Ngày tạo đơn, trạng thái tại thời điểm truy vấn. |
| Tỷ lệ hủy | Số đơn hiện đã hủy chia tổng số đơn cùng kỳ tạo. |
| Xu hướng hủy | Ngày ghi nhận hủy, gồm cả đơn được tạo trước kỳ. |
| Lượng thanh toán demo | `requested_amount` của giao dịch DEMO thành công trong kỳ thanh toán. Booking hủy sau khi thanh toán vẫn có sự kiện demo trong lịch sử. |
| Giá trị đặt phòng xác nhận | Phần thanh toán demo trong kỳ thuộc các đơn hiện xác nhận hoặc hoàn tất. |
| Ưu đãi và giá trị bình quân | Ưu đãi lấy từ snapshot của booking đã thanh toán demo. Bình quân bao gồm cả đơn được tất toán 0 ₫. |
| Phân bố hạng thành viên | Hạng hiện tại của khách có đơn tạo trong kỳ, mỗi khách tính một lần. |
| Tỷ lệ đêm đã đặt (ước tính) | Số đêm có booking xác nhận/hoàn tất và thanh toán thành công trên số đêm có thể bán ước tính. Mỗi phòng và ngày chỉ tính một lần. |

Ước tính sức chứa dùng phòng và hạng đang hoạt động, phòng tồn tại trước giờ nhận 12:00, và lịch chặn có hiệu lực chồng với khoảng 12:00–10:00 ngày sau. Ngày trả phòng là cận loại trừ của kỳ lưu trú. Trạng thái phòng và phân phòng được đọc theo bản ghi hiện tại. Bản ghi chưa đủ để suy ra công suất lưu trú thực tế, ADR, RevPAR, khoản hoàn tiền hoặc doanh thu đã ghi nhận. So sánh lượng thanh toán demo chỉ hiện khi cả hai kỳ cùng độ dài có giao dịch thành công và kỳ đang xem đã kết thúc. CSV xuất các chuỗi theo ngày, dùng chung bộ lọc và phép tổng hợp với dashboard.

## Timeline và yêu cầu nhận phòng

| API | Quyền và dữ liệu |
| --- | --- |
| `GET /api/v1/bookings/{code}/timeline` | CUSTOMER sở hữu booking. Trả snapshot, hạng phòng, ảnh bìa hiện tại, trạng thái thanh toán, mốc đã ghi nhận và tối đa 50 yêu cầu gần nhất. |
| `POST /api/v1/bookings/{code}/check-in-requests` | CUSTOMER sở hữu booking. Gửi `estimatedArrival` theo giờ địa phương dạng ISO, `earlyCheckInRequested` tùy chọn, `arrivalNote` tối đa 500 ký tự. |
| `POST /api/v1/bookings/{code}/check-in-requests/{id}/cancel` | CUSTOMER sở hữu booking, yêu cầu vẫn chờ xử lý. |
| `GET /api/v1/staff/check-in-requests` | STAFF/ADMIN. Lọc bằng `status`, `code`, `page` từ 0 và `size` tối đa 50. |
| `GET /api/v1/staff/check-in-requests/{id}` | STAFF/ADMIN. Chi tiết và dấu vết xử lý, giới hạn ở trường nghiệp vụ. |
| `POST /api/v1/staff/check-in-requests/{id}/review` | STAFF/ADMIN. `decision` là `APPROVED` hoặc `REJECTED`. Từ chối cần `rejectionReason` tối đa 300 ký tự. |

Mặc định mở lúc 00:00 theo giờ Việt Nam trước ngày đến 3 ngày. Có thể chỉnh `CHECK_IN_DAYS_BEFORE_ARRIVAL` từ 0 đến 30. Hạn gửi và duyệt là thời điểm trả phòng theo booking. Giờ đến dự kiến phải từ thời điểm hiện tại, từ ngày đến và trước giờ trả phòng. Đến trước giờ nhận trong ngày đến cần chọn đề nghị đến sớm. Giao diện dùng quyết định đủ điều kiện của backend.

Một booking chỉ có một yêu cầu đang chờ hoặc đã duyệt. Khi đã từ chối hoặc khách đã rút, khách có thể gửi lại nếu còn đủ điều kiện. Khi booking không còn xác nhận hoặc đã tới giờ trả phòng, yêu cầu đang chờ hiển thị `INVALIDATED`. Đây là trạng thái suy ra từ booking và thời điểm truy vấn, giữ nguyên lịch sử gửi. Các lệnh ghi dùng cùng khóa booking với thanh toán và scheduler. Nhân viên duyệt kế hoạch đón khách. Booking, phân phòng và scheduler giữ nguyên vòng đời hiện có.

## Checkpoint hoàn tất

- Backend mới tại `com.hotelpms.analytics` và `com.hotelpms.booking.checkin`. Bổ sung V6, cấu hình cửa sổ yêu cầu, truy vấn PaymentAttempt và quyền API.
- Frontend mới tại `features/analytics` và `features/checkin`. Điều hướng qua Admin, Staff và chi tiết trong Kỳ nghỉ của tôi.
- Analytics 2 test, Check-in/Timeline 9 test. Maven verify 309/309, frontend 108/108, lint và production build thành công.
- PostgreSQL 17.10 trên database QA riêng đã áp dụng V1–V6. Spring Boot và Vite preview khởi động thành công.
- Browser QA xác minh Admin đổi kỳ báo cáo, Customer gửi giờ đến 14:00, Staff duyệt, Customer tải lại và thấy đã duyệt. Booking vẫn `CONFIRMED`. Timeline một cột ở 390 px.
- Đối chiếu DB và API. Hai đơn được tạo, một thanh toán demo thành công, một đơn chờ. Snapshot 2.500.000 ₫ giảm 250.000 ₫ theo hạng và 400.000 ₫ voucher, thanh toán 1.850.000 ₫. Gửi lại khóa thanh toán không tăng số giao dịch. PDF trả 200, CSV khớp số liệu. Đổi giá thử làm báo giá mới thành 2.700.000 ₫, snapshot và KPI cũ giữ 1.850.000 ₫. Giá QA đã được trả về giá trước thử.
- Kiểm tra quyền thực tế. Customer/Staff truy cập Analytics trả 403. Customer duyệt yêu cầu trả 403. Customer khác đọc timeline trả 404.
