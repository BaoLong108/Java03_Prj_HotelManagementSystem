# Hỗ trợ khách hàng Hotel PMS

Widget **Hỗ trợ** có trên website khách. Nhân viên mở **Hỗ trợ khách hàng** tại `/staff/support`. Admin quản lý nội dung tại `/admin/support/faq`.

## Chạy local

Khởi động PostgreSQL, Spring Boot với profile `dev` và Vite theo hướng dẫn của repo, hoặc dùng `docker compose up --build`. Dùng các tài khoản Customer, Staff và Admin riêng. Theo mặc định Vite chuyển HTTP và WebSocket đến cổng 8080.

| Biến backend | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `AI_ENABLED` | `false` | Bật adapter nhà cung cấp |
| `AI_PROVIDER` | `openai-compatible` | Tên adapter hiện có |
| `AI_API_KEY` | trống | Secret từ môi trường chạy backend |
| `AI_MODEL` | trống | Model của nhà cung cấp hỗ trợ function calling |
| `AI_BASE_URL` | `https://api.openai.com/v1` | Base URL HTTPS |
| `AI_REQUEST_TIMEOUT` | `12s` | Hạn cả request, tối đa 25 giây |
| `AI_MAX_OUTPUT_TOKENS` | `400` | Giới hạn token cho quyết định chọn công cụ |
| `AI_MAX_CONVERSATION_CONTEXT` | `6` | Tối đa 12 câu hỏi trước của khách đã lọc thông tin nhận dạng phổ biến |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Danh sách origin trình duyệt được phép mở WebSocket |

Bật AI bằng cách cung cấp model và khóa hợp lệ trong môi trường backend, sau đó khởi động lại. Adapter dùng Chat Completions với function calling và chấp nhận tối đa một công cụ mỗi lượt. Câu trả lời FAQ trích bản ghi công khai. Phòng, ảnh, giá và trạng thái booking lấy từ PMS. Provider chỉ nhận câu hỏi đã lọc, một phần tin nhắn do khách nhập và tiêu đề FAQ công khai. Bản ghi booking, trạng thái thanh toán và câu trả lời nội bộ được xử lý trong backend.

Khi provider chưa cấu hình hoặc lỗi, widget cho biết trạng thái và vẫn dùng được các thao tác tra cứu, FAQ công khai và chat nhân viên. Câu tìm phòng có đủ ngày và số khách dùng được định dạng `20/11 đến 22/11`, `20/11/2026 đến 22/11/2026` hoặc ISO. Ngày viết tắt dùng năm hiện tại theo giờ khách sạn. Ghi rõ năm khi kỳ nghỉ qua giao thừa. Những câu hỏi tự do cần diễn giải ngữ cảnh phụ thuộc vào provider. Admin chỉ đưa vào FAQ thông tin được phép công bố. FAQ nhận/trả phòng ban đầu dùng giờ của luồng đặt phòng hiện tại.

## Vòng đời và dữ liệu

`AI_ACTIVE → WAITING_STAFF → STAFF_ACTIVE → CLOSED`. Khách có tối đa một hội thoại mở. Nhân viên thấy yêu cầu chờ và nhận bằng cập nhật có điều kiện trong PostgreSQL. Lịch sử đầy đủ hiển thị cho người đã nhận phiên. Khách có thể để lại tin nhắn khi chưa có nhân viên. Sau khi đóng, khách có thể tạo hội thoại mới và mở lại lịch sử theo quyền.

Migration `V10` tạo `support_conversations`, `support_messages`, `hotel_faqs` và `support_rate_windows`. Mỗi tin nhân viên hoặc khách có UUID do client sinh. Lịch sử dùng ID tăng dần và chỉ đánh dấu đã đọc đến tin client đã tải. Tác vụ chạy hằng ngày xóa hội thoại và tin nhắn sau 90 ngày không hoạt động. FAQ được giữ cho đến khi Admin cập nhật hoặc bỏ công khai.

## REST và WebSocket

Tiền tố HTTP là `/api/v1`.

| Endpoint | Vai trò / chức năng |
| --- | --- |
| `GET /support/status` | Trạng thái cấu hình AI và số nhân viên trực tuyến |
| `POST /support/ai/message` | FAQ, tra cứu phòng/giá, booking cá nhân theo JWT và phản hồi AI |
| `GET, POST /support/conversations` | Customer xem lịch sử hoặc mở phiên |
| `POST /support/conversations/{id}/request-staff` | Customer gửi yêu cầu cho nhân viên |
| `GET /staff/support/conversations` | Staff/Admin xem hàng chờ hoặc hội thoại mình phụ trách |
| `POST /staff/support/conversations/{id}/claim`, `/close` | Staff/Admin nhận hoặc kết thúc |
| `GET /{prefix}/conversations/{id}`, `/messages` | Chi tiết hoặc trang tin nhắn theo vai trò. `before` và `after` là cursor ID |
| `POST /{prefix}/conversations/{id}/messages`, `/read` | Gửi dự phòng qua HTTP hoặc cập nhật đã đọc. `prefix` là `support` hay `staff/support` |
| `GET, POST /admin/support/faqs`, `PUT /admin/support/faqs/{id}` | Admin tìm, tạo, sửa và bật/tắt công khai |

Kết nối WebSocket tại `/api/v1/support/socket`. Frame đầu là `{"type":"auth","token":"<JWT>"}` và server trả `ready`. Có `ping/pong`, `send` với `conversationId`, `clientId`, `content` và `staff` khi gửi từ inbox. Server xác minh token, vai trò và quyền trên hội thoại trước khi lưu. `ack` xác nhận tin đã lưu, còn `changed` và `presence` báo client đọc lại qua REST có xác thực. Frame báo thay đổi không chứa ID hay nội dung hội thoại. Reconnect chạy lại truy vấn tin theo cursor và gộp theo ID. Dùng `wss:` sau reverse proxy HTTPS.

JWT hết hạn hoặc tài khoản thay đổi quyền sẽ ngắt phiên. Presence hiện tính theo các phiên mở inbox trong một instance backend, mỗi nhân viên được đếm một lần. Mỗi socket gửi một frame tại một thời điểm theo thứ tự hàng đợi, giữ tối đa 32 frame chờ. Khi đường truyền lỗi hoặc không theo kịp, server gỡ phiên đó và client kết nối lại để đọc tin đã lưu. Hệ thống giới hạn độ dài tin, tốc độ gửi, số request AI đồng thời, kích thước frame và lịch sử mỗi trang. Với reverse proxy, giới hạn guest dùng địa chỉ peer mà server xác nhận. Muốn giới hạn theo từng IP thật, cấu hình trusted proxy trong môi trường triển khai.

## Kiểm thử

- Backend `./mvnw verify` hoặc `mvnw.cmd verify` trên Windows. `SupportIntegrationTest` chạy với PostgreSQL nhúng và WebSocket thật, gồm nhiều tab gửi đồng thời, nhận hội thoại cạnh tranh, phân trang, quyền truy cập và chuyển sang Staff trong lúc provider đang xử lý. `SupportOutboxTest` kiểm tra thứ tự, áp lực hàng đợi và lỗi truyền. `ConciergeProviderTest` kiểm tra request HTTP và timeout với transport mock.
- Frontend `npm test`, `npm run lint` và `npm run build`.
- QA thủ công mở hai tab đăng nhập riêng. Customer yêu cầu Staff, Staff nhận phiên, hai bên gửi tin, sau đó đóng tab Staff, để lại tin và mở lại inbox. Kiểm tra khách chỉ xem được booking của họ.
- Kiểm thử provider thật cần khóa và model hợp lệ. Hỗ trợ Việt/Anh chưa được nghiệm thu như một tính năng riêng.
