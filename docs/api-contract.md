# Quy ước API chung

Đây là quy ước ban đầu để các module có cùng cách giao tiếp. Endpoint nghiệp vụ cụ thể được chốt trong PR của từng task trước khi frontend tích hợp.

- Prefix: `/api/v1`. JSON dùng `camelCase`; ID dùng `Long` ở Java và JSON number, khớp `BIGINT` trong schema V1.
- Thời gian gửi/nhận theo ISO 8601 có offset, ví dụ `2026-10-08T12:00:00+07:00`. Khoảng thời gian luôn gồm `startAt`, `endAt` và `startAt < endAt`.
- Tiền là số thập phân theo VND, không dùng `double` ở backend. Response quote/booking dùng tên trường thống nhất: `baseAmount`, `discountRate`, `payableAmount`.
- Thành công trả HTTP 2xx và một DTO rõ ràng. Validation trả 400; thiếu/không hợp lệ token trả 401; không đủ role trả 403; không tìm thấy trả 404; xung đột đặt/chặn phòng trả 409.
- Lỗi có dạng `{"code":"ROOM_NOT_AVAILABLE","message":"...","details":{}}`. Không trả stack trace hoặc dữ liệu nhạy cảm cho client.
- JWT chỉ chứa định danh và role cần thiết, không chứa thông tin cá nhân hoặc số tiền. Backend kiểm tra quyền ở endpoint/service; ẩn menu trên frontend chỉ để cải thiện trải nghiệm.
- Payment callback cần đối chiếu booking, amount, checksum và mã giao dịch; retry không được ghi nhận tiền hoặc đổi trạng thái hai lần.
- `GUEST` là request chưa đăng nhập: được xem loại phòng, tìm phòng và lấy quote. Tạo hold/booking và thanh toán yêu cầu đăng nhập `CUSTOMER`; `STAFF` và `ADMIN` dùng quyền riêng. Không phát JWT hoặc tạo user DB cho Guest.

| Vai trò | Phạm vi API dự kiến |
| --- | --- |
| `GUEST` (ẩn danh) | Xem loại phòng, tìm phòng, lấy quote |
| `CUSTOMER` | Quyền Guest, tạo hold/booking, thanh toán và xem dữ liệu của chính mình |
| `STAFF` | Tra cứu booking, xem lịch phòng, tạo/mở RoomBlock theo quyền |
| `ADMIN` | Quản lý danh mục loại phòng/phòng vật lý và quyền quản trị |

Các API Auth đã có hợp đồng và ví dụ trong [docs/auth.md](auth.md). Trước khi code các luồng phụ thuộc nhau, cập nhật ví dụ request/response trong PR của endpoint nghiệp vụ mới.

Contract Loyalty, bảng quyền của endpoint hiện có và policy dùng cho module tương lai nằm trong [bàn giao M1-04/M1-05](m1-sprint1-handover.md). Bảng phạm vi dự kiến ở trên chưa mở endpoint nghiệp vụ trong `SecurityConfig`; từng API cần được triển khai và phân quyền cụ thể trước khi tích hợp.
