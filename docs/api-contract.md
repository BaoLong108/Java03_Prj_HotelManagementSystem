# Quy ước API chung

Đây là quy ước ban đầu để các module có cùng cách giao tiếp. Endpoint nghiệp vụ cụ thể được chốt trong PR của từng task trước khi frontend tích hợp.

- Prefix: `/api/v1`. JSON dùng `camelCase`; ID là số hoặc UUID phải thống nhất trong PR migration đầu tiên.
- Thời gian gửi/nhận theo ISO 8601 có offset, ví dụ `2026-10-08T12:00:00+07:00`. Khoảng thời gian luôn gồm `startAt`, `endAt` và `startAt < endAt`.
- Tiền là số thập phân theo VND, không dùng `double` ở backend. Response quote/booking dùng tên trường thống nhất: `baseAmount`, `discountRate`, `payableAmount`.
- Thành công trả HTTP 2xx và một DTO rõ ràng. Validation trả 400; thiếu/không hợp lệ token trả 401; không đủ role trả 403; không tìm thấy trả 404; xung đột đặt/chặn phòng trả 409.
- Lỗi có dạng `{"code":"ROOM_NOT_AVAILABLE","message":"...","details":{}}`. Không trả stack trace hoặc dữ liệu nhạy cảm cho client.
- JWT chỉ chứa định danh và role cần thiết, không chứa thông tin cá nhân hoặc số tiền. Backend kiểm tra quyền ở endpoint/service; ẩn menu trên frontend chỉ để cải thiện trải nghiệm.
- Payment callback cần đối chiếu booking, amount, checksum và mã giao dịch; retry không được ghi nhận tiền hoặc đổi trạng thái hai lần.

Trước khi code các luồng phụ thuộc nhau, cập nhật ví dụ request/response trong PR của endpoint. Tài liệu này chưa khẳng định các API nghiệp vụ đã tồn tại.
