# Checklist nghiệm thu Sprint 2 (19–25/10/2026)

Cập nhật kỹ thuật ngày 26/10/2026 bởi Long sau khi tích hợp code đã có của nhóm và hoàn thiện phần còn thiếu theo yêu cầu. Chi tiết task, nguồn commit và DTO: [Sprint 2 completion](sprint2-completion.md). File kế hoạch gốc giữ nguyên để nhóm xác nhận báo cáo.

## RoomBlock / availability — M2-06, M2-05

- [x] Guest 401, Customer 403; Staff/Admin tạo và release, audit actor đúng JWT.
- [x] Block chỉ loại phòng khỏi availability đúng khoảng; release cho phép tìm lại nếu không vướng booking.
- [x] Từ chối block chồng live hold/CONFIRMED; không sửa dữ liệu booking.
- [x] Release chỉ mở block tương ứng, không thay booking khác, kể cả hold đã hết hạn.
- [x] Booking và block khóa cùng physical room; race chỉ một request thành công.
- [x] Khoảng `[startAt,endAt)`, hai lượt 12:00–10:00 kề nhau dùng lại cùng phòng.
- [x] FE tìm theo availability thật, không suy ra availability từ catalog activeRoomCount.

## Booking — M3-03, M3-04, M3-07

- [x] Customer chọn RoomType; backend gán physical room, không lộ số phòng trong DTO Customer.
- [x] Một transaction khóa phòng và tạo PENDING_PAYMENT, hold 15 phút tính sau khi lấy khóa, snapshot Quote.
- [x] Hai request tranh một phòng: một 201, một 409, không tạo booking dở dang.
- [x] Job EXPIRED chạy lặp an toàn; hold hết hạn có thể được đặt lại.
- [x] Job COMPLETED chỉ khi đã trả tiền và qua endAt, khóa/reload mỗi booking trong transaction riêng.
- [x] Owner check ở server kể cả Customer có thêm role; quyền đọc vận hành không cấp quyền thanh toán/hủy booking khác.
- [x] Quote và snapshot thống nhất hotel timezone, số đêm, tier/giá; snapshot cũ giữ nguyên khi giá thay đổi.

## Payment demo / PDF — M4-03, M4-06

- [x] Số tiền và owner từ DB, client không quyết định amount/user/status.
- [x] Một SUCCEEDED xác nhận booking; cùng key trả attempt cũ, kể cả đồng thời.
- [x] Khác key cũng không tạo hai SUCCEEDED; unique constraint và khóa booking bảo vệ.
- [x] FAILED không xác nhận booking; retry bằng key mới, Guest/Staff/khác owner bị chặn.
- [x] Payment-vs-expiry chỉ CONFIRMED có payment hoặc EXPIRED chưa trả; không paid + expired.
- [x] Endpoint confirm cũ không vượt qua payment gate; FE không tự xác nhận thanh toán.
- [x] PDF thật có booking, khoảng ở, snapshot, payment và số đã trả; owner/Staff/Admin; chưa trả tiền trả 409.

## Staff UI — M5-04, M5-03

- [x] Staff/Admin xem Calendar theo phòng vật lý, ngày/tuần và loại phòng qua API thật; Customer bị chặn.
- [x] Phân biệt booking, trạng thái và block; không coi vùng trống là bảo đảm availability.
- [x] Loading/empty/error/abort/403 đúng, không fallback dữ liệu giả khi API lỗi.
- [x] Lookup mã booking/SĐT đầy đủ, không liệt kê toàn bộ; contact đã che ở server và FE.
- [x] PDF cho booking đã thanh toán; không thêm nút check-in/check-out.

## Validation và bàn giao

- [x] Backend verify: 235 tests PASS trên PostgreSQL test riêng, không skip; package thành công.
- [x] Frontend: 61 tests PASS, lint và production build thành công.
- [x] Packaged API startup/Flyway thành công trên DB smoke riêng.
- [x] Browser thật: Customer quote → hold → demo payment → CONFIRMED; Staff Calendar và Lookup thấy đúng booking, fixture mode tắt.
- [x] Contract/README cập nhật, nguồn member commits được ghi nhận, file cá nhân không nằm trong commit.

Trạng thái GitHub CI và merge SHA lấy trực tiếp từ PR tích hợp và [Actions](https://github.com/BaoLong108/Java03_Prj_HotelManagementSystem/actions). Chỉ merge dev sau khi CI của revision hiện tại đạt, không lấy local PASS thay CI. Các task Loyalty award/lịch sử và VNPay còn theo Sprint 3; UI quản trị/KPI ngoài WBS S2 tiếp tục theo kế hoạch.
