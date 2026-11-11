# Tích hợp Sprint 3–4

## Nguồn và trách nhiệm

Đối chiếu workbook WBS bốn sprint ở `outputs/01a11bd1-09bd-7d42-a507-ef5e453c6794/Ke_hoach_PMS_4_sprint_theo_tuan.xlsx`, sheet Sprint 3 và Sprint 4. Audit remote ngày 11/11/2026.

| Thành viên | Mã nguồn đã có khi audit | Công việc chưa được thành viên hoàn thiện trên remote |
| --- | --- | --- |
| Bảo Long | BaoLong 776cf10 và working tree Floor Map, Rate Calendar, Analytics, Timeline/Online Check-in. | M1-08/M1-09 cần nghiệm thu sau tích hợp các thành viên và CI Compose. |
| Phước Sang | PhuocSang 44174a6, nhánh sprint2/PhuocSang-M2-04 cb7d727 có availability và tests. | M2-07 UI sửa/ẩn phòng, xem block sắp tới. M2-08 nghiệm thu bản tích hợp. |
| Thanh Bình | PhanThanhBinh 2c88871, đã có trong dev. | Không có commit mới Sprint 3–4. M3-06/M3-08 đã được bản tích hợp Sprint 2 xử lý trước. |
| Đình Đạt | NguyenDinhDat add219c thêm Payment demo rời rạc. Entity/repository/snapshot trước đó đã được cherry-pick vào dev. | WBS M4-04 là VNPay URL, M4-05 IPN, M4-07 email, M4-08 cuối tuần. add219c chưa có adapter Booking thực tế và chưa triển khai VNPay. |
| Thông Đạt | DatHVT-S3 6674fb1, PR #7, M5-05 và M5-08 có CI xanh. | M5-06 Dashboard, M5-07 UI audit RoomBlock, M5-09 CSV lịch đặt phòng. |

## Các phần bổ sung khi tích hợp

- Giữ cùng một luồng DemoPaymentService đang phục vụ Booking, Voucher và PDF. Dùng entity/repository/snapshot và PaymentAttemptResponse của Đình Đạt. Tích hợp hợp đồng `POST /api/v1/payments` theo bookingId và `GET /api/v1/payments/attempts/{id}` qua adapter PaymentAttemptService dùng cùng booking lock, snapshot, voucher, xác nhận và idempotency với UI. Amount từ client phải khớp snapshot. Code demo rời rạc 600 dòng ở add219c được thay bằng adapter này để tránh hai bộ xử lý thanh toán. Các nhánh nguồn của thành viên giữ nguyên.
- M2-07 có thêm sửa số phòng/tầng, xác nhận ẩn và xem block hiện tại/sắp tới. Thao tác ẩn và cấp phòng dùng cùng khóa physical room. Cấp phòng đọc lại trạng thái hoạt động sau khi khóa.
- M5-05/M5-08 dùng code Thông Đạt, giữ tra cứu và bổ sung tab theo ngày. Lịch và màn chặn dùng API thật. Floor Map giữ route riêng.
- M5-06 dùng Analytics hiện có. Giá trị demo và ước tính đêm đã đặt được ghi nhãn. Chi tiết công thức ở `analytics-checkin-qa.md`.
- M5-07 đọc lịch sử `history=true`, hiển thị actor ID chặn/mở, mốc thời gian và lý do. Quyền STAFF/ADMIN.
- M5-09 API ADMIN `GET /api/v1/admin/bookings/export.csv?from=...&to=...&roomTypeId=...&status=...`. Hai ngày đầu/cuối được bao gồm theo giờ khách sạn. Booking và block có khoảng giao kỳ được xuất để đối chiếu. Tối đa 366 ngày/10.000 dòng, có bảo vệ CSV formula và giữ nguyên giá snapshot.
- M4-08 đã có loại quy tắc WEEKEND trong Rate Calendar, dùng chung resolver với quote và snapshot.
- Sửa sai khác nano/micro-giây ở succeededAt của payment. Phản hồi đầu và replay dùng cùng độ chính xác PostgreSQL. Giữ kiểm tra replay toàn bộ response trong smoke Compose.

## VNPay Sandbox

Mặc định tắt. Khai báo `VNPAY_ENABLED=true`, `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_RETURN_URL` trong môi trường local hoặc file .env được ignore. Nút Sandbox chỉ hiện cho Customer khi cấu hình đầy đủ. Chỉ chấp nhận host thanh toán Sandbox cố định.

- `POST /api/v1/payments/vnpay/checkout` nhận bookingCode, idempotencyKey. JWT xác định owner. Lưu payment attempt, lấy amount từ snapshot. Một booking chỉ dùng lại một checkout đang chờ; tiền 0 ₫ dùng luồng demo đã có.
- `GET /api/v1/payments/vnpay/ipn` là callback public được xác thực bằng HMAC-SHA512. Kiểm merchant, amount nhân 100, mã giao dịch và trạng thái. Khóa cùng booking với payment/expiry/cancellation. Chỉ một payment SUCCEEDED được phép.
- Return về `/payment-return` chỉ đọc lại trạng thái Booking có quyền sở hữu. Các trường query do trình duyệt gửi không xác nhận thanh toán.
- IPN đã xử lý trả RspCode 02. Sai chữ ký 97, sai amount 04, không có giao dịch 01. Sai dữ liệu hoặc trùng mã giao dịch trả 99.
- Thành công đến sau hold, sau thanh toán khác hoặc sau thay đổi snapshot được ghi `vnpay_checkouts.outcome=REVIEW_REQUIRED`, có response/transaction/time để đối soát. Booking được giữ theo vòng đời hiện tại. Cần đối soát với merchant để xử lý tiền nếu trường hợp này xảy ra.
- API luồng demo từ chối khi đang có checkout VNPay chờ xử lý.

Đăng ký IPN URL HTTPS công khai với merchant sandbox. Localhost cần một địa chỉ thử nghiệm do nhóm cấu hình để server VNPay có thể gọi tới. Tài liệu nguồn là [đặc tả PAY 2.1.0 của VNPAY](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).

## Email và PDF

V7 thêm outbox một hàng trên mỗi booking, tự tạo trong cùng giao dịch khi PENDING_PAYMENT chuyển CONFIRMED. Gửi sau đó bởi worker riêng, tối đa 5 lần với khoảng cách 5 phút, mỗi lượt tối đa 10 thư. SQL khóa SKIP LOCKED ngăn hai worker gửi đồng thời. SMTP lỗi ghi FAILED và không hoàn tác Booking/Payment. Khi SMTP đã nhận thư nhưng tiến trình dừng trước commit DB, thư có thể được gửi lại trong lần retry.

Mặc định tắt. Bật bằng `CONFIRMATION_MAIL_ENABLED=true`, cấu hình `CONFIRMATION_MAIL_FROM`, `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD` và `SMTP_AUTH/SMTP_STARTTLS` phù hợp sandbox. Thư đính kèm đúng PDF xác nhận snapshot hiện có. Không cấu hình dữ liệu đăng nhập thật trong repository. Email cần tránh bật trên database chứa lịch sử demo chưa gửi nếu không muốn worker xử lý hàng chờ đó.

## Bảo toàn Git và nghiệm thu

Worktree gốc BaoLong giữ nguyên toàn bộ file chưa commit và file cá nhân. Bản tích hợp nằm ở worktree riêng, đưa code local vào commit để review/CI trước khi cập nhật dev. Các bản ghi QA nằm trong PostgreSQL thử nghiệm riêng. Không sửa migration V1–V6 đã có. V7 thêm checkout và outbox.

Kết quả chạy, commit dev và CI được cập nhật sau khi hoàn thành nghiệm thu.
