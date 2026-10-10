# M1-01, M1-02, M1-03 — Auth API

Base URL local: `http://localhost:8080`; prefix `/api/v1/auth`. Frontend Vite có thể gọi đường dẫn tương đối qua API client hiện có. Các API dùng JSON, không tạo HTTP session/cookie.

## Đăng ký

`POST /api/v1/auth/register` — không cần đăng nhập.

```json
{
  "email": "long@example.com",
  "password": "ExamplePassword123!",
  "fullName": "Nguyễn Ngọc Bảo Long",
  "phoneNumber": "0901234567"
}
```

Email phải đúng định dạng, tối đa 255 ký tự và được lưu chữ thường. `fullName` bắt buộc, tối đa 150 ký tự; `phoneNumber` tùy chọn, tối đa 30 ký tự. Mật khẩu không bị trim: ít nhất 8 ký tự, tối đa 72 byte UTF-8 theo giới hạn BCrypt. Password được hash bằng BCrypt cost 12.

Đăng ký chỉ cấp role `CUSTOMER`, tạo `customer_profiles` mặc định `STANDARD`, 0 điểm, 0 chi tiêu. Client không được chọn role staff/admin. User, user_roles và profile được ghi trong cùng transaction; email trùng (kể cả khác chữ hoa/thường hay hai request đồng thời) trả 409.

Trả **201 Created** với cấu trúc giống login bên dưới.

## Đăng nhập

`POST /api/v1/auth/login` — không cần đăng nhập.

```json
{
  "email": "long@example.com",
  "password": "ExamplePassword123!"
}
```

Trả **200 OK**:

```json
{
  "accessToken": "<signed-jwt>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": 4,
    "email": "long@example.com",
    "fullName": "Nguyễn Ngọc Bảo Long",
    "roles": ["CUSTOMER"]
  }
}
```

Sai mật khẩu, tài khoản không tồn tại hoặc đã bị khóa cùng trả `401 INVALID_CREDENTIALS`. Một tài khoản có thể có nhiều role; frontend đọc mảng `user.roles`. `expiresIn` là số giây, mặc định 3600 và có thể đổi bằng `JWT_ACCESS_TOKEN_TTL_SECONDS` (60–86400).

## Tài khoản hiện tại

`GET /api/v1/auth/me`, header `Authorization: Bearer <accessToken>`.

Trả **200 OK**, chỉ chứa object `user` như trên, không bọc trong trường `user`. ID lấy từ token đã xác minh; client không truyền userId để chọn tài khoản khác.

JWT ký HS256 và chỉ chứa `sub` (ID user), `roles`, `iss`, `iat`, `exp`; không chứa email, SĐT, mật khẩu hoặc thông tin thanh toán. Server kiểm tra chữ ký, thuật toán, issuer, thời hạn, các claim bắt buộc và trạng thái tài khoản. Quyền thực tế được đọc lại từ DB mỗi request; khóa tài khoản có hiệu lực cả với token đã cấp. Logout ở frontend xóa token/session phía client; task này chưa có refresh token hoặc danh sách thu hồi token theo thiết bị.

## Lỗi chung

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Dữ liệu không hợp lệ",
  "details": {"password": "Mật khẩu cần ít nhất 8 ký tự và tối đa 72 byte UTF-8"}
}
```

| HTTP | code | Xử lý ở frontend |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Hiện lỗi theo tên field trong `details` |
| 400 | `MALFORMED_JSON` | Kiểm tra JSON request |
| 409 | `EMAIL_ALREADY_EXISTS` | Báo email đã được đăng ký |
| 401 | `INVALID_CREDENTIALS` | Báo email/mật khẩu không đúng |
| 401 | `UNAUTHORIZED` | Yêu cầu đăng nhập lại; token thiếu/sai/hết hạn hoặc tài khoản bị khóa |
| 403 | `FORBIDDEN` | Báo không đủ quyền |

`GUEST` là người chưa đăng nhập, không có row user/role và không nhận JWT. Các vai trò tài khoản là `CUSTOMER`, `STAFF`, `ADMIN`. M1-05 sẽ bổ sung quy tắc RBAC cho API nghiệp vụ; hiện các route ngoài health và ba API Auth trên vẫn bị từ chối.

## Kiểm thử

Chuyển vào thư mục `backend`, chạy `./mvnw verify` (Windows: `.\mvnw.cmd verify`). Test khởi động PostgreSQL thật và HTTP server ở port ngẫu nhiên, chạy Flyway V1 rồi Hibernate `validate`. Test không sửa database local đang phát triển; chỉ khi đặt `TEST_DB_URL` thì nó dùng database test bên ngoài. Database test bên ngoài phải là DB riêng vì bộ test tạo dữ liệu tài khoản.

Không sửa migration V1 đã chia sẻ. Entity `User`, `Role`, `CustomerProfile` map đúng schema hiện có; `LoyaltyTier` mới chỉ định nghĩa ba tên tier, quy tắc tính điểm/discount thuộc M1-04.
