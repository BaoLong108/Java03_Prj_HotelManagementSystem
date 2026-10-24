# Checklist tích hợp cuối Sprint 2 (19–25/10/2026)

Owner điều phối: Nguyễn Ngọc Bảo Long. Các thành viên khác chưa bắt đầu Sprint 2 tại thời điểm lập checklist. Mọi ô chưa đánh dấu dưới đây là **ca cần chạy khi module sẵn sàng**, không phải kết quả FAIL hiện tại. Không tạo test Java bị skip để đại diện chức năng chưa triển khai.

## 1. Điều kiện bắt đầu mỗi lượt tích hợp

- [ ] Owner xác nhận implementation và PR thực sự tồn tại; chưa có thì tiếp tục ở trạng thái CHỜ OWNER.
- [ ] Các task Sprint 1 còn thiếu của module đã được xử lý hoặc dependency/giới hạn được thống nhất trong PR.
- [ ] Chốt method/path, request/response, kiểu tiền, timezone, mã lỗi và role với bên tiêu thụ theo [contract](sprint2-long-integration-contracts.md).
- [ ] Fixture nằm trong PostgreSQL test riêng, có RoomType/PhysicalRoom và tài khoản Guest/Customer/Staff/Admin; không dùng dữ liệu local cần giữ.
- [ ] Adapter thật được owner cung cấp; không coi quote 404 từ Room stub hoặc calendar placeholder là tính năng đã tích hợp.

## 2. RoomBlock / availability — Sang, Binh (M2-06, M2-05 và phần S1 còn lại)

Chỉ chạy sau khi owner có API/query và giao contract:

- [ ] Staff tạo block theo physicalRoomId/startAt/endAt/reason; Guest 401, Customer 403, actor đúng JWT. Security/actor hiện đã có test riêng của Long; vẫn chạy lại khi owner đổi controller/service.
- [ ] Block đang active loại phòng tương ứng khỏi kết quả trống trong đúng khoảng; ngoài khoảng vẫn tính được.
- [ ] Block mới đụng CONFIRMED hoặc hold còn hạn phải bị từ chối, dữ liệu booking không đổi.
- [ ] Release chỉ mở block được chọn, giữ audit actor và không sửa booking khác.
- [ ] Hold tạo đồng thời với block dùng cùng quy tắc khóa/transaction; không có cả booking và block chồng lấn thành công.
- [ ] Kiểm tra khoảng `[startAt,endAt)` và hai kỳ ở kế nhau: ngày D 12:00 đến D+1 10:00, D+1 12:00 đến D+2 10:00.
- [ ] Không sử dụng catalog activeRoomCount như số phòng còn trống theo thời gian.

## 3. Booking — Binh (M3-03, M3-04, M3-07)

Chỉ chạy sau khi Binh thông báo phần giữ chỗ/job/concurrency sẵn sàng; helper/test sẵn có trên dev chưa là nghiệm thu toàn bộ task:

- [ ] Customer chọn RoomType; backend gán physicalRoom nội bộ và không lộ số phòng trong DTO khách.
- [ ] Tạo PENDING_PAYMENT cùng holdExpiresAt 15 phút và snapshot tiền trong một transaction.
- [ ] Hai request tranh đúng một phòng: một thành công, một 409; không có dữ liệu dở dang.
- [ ] Job hết hạn chuyển EXPIRED, chạy lặp không tạo tác dụng phụ; phòng đặt lại được.
- [ ] Job tự COMPLETED chỉ với booking đã thanh toán và qua endAt, chạy lặp an toàn. Không cần nút check-in/check-out.
- [ ] Kiểm tra owner tại server: Customer khác và tài khoản nhiều role không thao tác booking của người khác qua route Customer.
- [ ] Quote và Booking thống nhất số đêm/timezone/giá/tier; snapshot cũ không đổi khi giá hoặc hạng thay đổi.

## 4. Payment demo — Đình Đạt, Binh (M4-03)

CHỜ IMPLEMENTATION Payment; hiện không có payment test tích hợp nào được tuyên bố đã chạy:

- [ ] Resolve booking theo identifier đã chốt, đối chiếu owner và payable_amount ở DB.
- [ ] Thanh toán demo thành công tạo một SUCCEEDED và một lần chuyển CONFIRMED.
- [ ] Gọi lặp cùng idempotency key trả kết quả cũ, không ghi tiền lần hai; gọi đồng thời cũng được bảo vệ.
- [ ] Một booking không có hai giao dịch thành công dù dùng key khác.
- [ ] Thanh toán thất bại không xác nhận booking; request giả userId/amount/status không nâng quyền hoặc đổi số tiền.
- [ ] Đối chiếu race payment gần holdExpiresAt với job: chỉ một trạng thái kết thúc hợp lệ theo contract đã chốt.
- [ ] FE return URL không tự xác nhận tiền; không sử dụng endpoint confirm WIP như chứng cứ đã trả tiền.

## 5. Staff Calendar / tra cứu — Thông Đạt, Sang, Binh (M5-04, M5-03)

CHỜ API lịch/tra cứu tương ứng; chỉ thống nhất DTO khi backend sẵn sàng:

- [ ] Staff/Admin xem dữ liệu thật theo từng phòng vật lý và khoảng tuần/ngày; Customer bị chặn.
- [ ] Phân biệt booking với RoomBlock và trạng thái, không coi vùng trống/chưa tải được là available.
- [ ] Loading/empty/error hiển thị đúng; GET request dùng đúng param, tránh gắn actor hay quyền từ FE.
- [ ] API tra cứu hỗ trợ identifier đã chốt; chỉ hiển thị dữ liệu cá nhân trong phạm vi cần thiết.
- [ ] UI chặn/mở chỉ triển khai khi contract/backend nghiệp vụ đã nghiệm thu; không ép Calendar gọi API chưa có.

## 6. Chốt cuối tuần — Long điều phối

- [ ] Chỉ review PR tồn tại, đủ DoD và evidence của owner; không tự đánh dấu task xong vì branch đã merge hoặc CI xanh.
- [ ] Chạy bộ security/contract của Long, toàn bộ backend regression, frontend lint/test/build và startup/Flyway trên PostgreSQL test.
- [ ] Ghi lại task DONE/PARTIAL/BLOCKED, lỗi tái hiện, người phụ trách và dependency còn chờ; phân biệt task chưa phát triển với regression.
- [ ] Lưu báo cáo/test log và cập nhật WBS sau khi owner xác nhận. Merge qua quy trình nhóm, không force-push nhánh thành viên.

Trong đợt chuẩn bị của Long, chỉ commit/push BaoLong. Không cập nhật dev hoặc nhánh thành viên khác. Loyalty award M1-06 và lịch sử M1-07 vẫn theo Sprint 3; chưa đưa thêm vào scope này.
