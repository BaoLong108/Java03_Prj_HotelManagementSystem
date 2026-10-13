# Frontend Hotel PMS Mini

Frontend mẫu dùng React + TypeScript + React Router + Vite. Đây là nền chung để nhóm phát triển module theo cùng bố cục, component và cách gọi API.

## Chạy

```powershell
# Tại gốc repo, khởi động PostgreSQL theo README gốc trước.
cd backend
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'

# Terminal khác:
cd frontend
npm ci
npm run dev
```

Mở `http://localhost:5173`. Vite proxy `/api` và `/actuator` sang `http://localhost:8080`; không cần đổi URL ở từng module. Backend ở host khác: sao chép `.env.example` thành `.env.local`, đặt `VITE_API_BASE_URL`, rồi khởi động lại Vite; cấu hình `CORS_ALLOWED_ORIGINS` tương ứng ở backend. Máy chưa có database vẫn mở được giao diện và thấy trạng thái kết nối bị gián đoạn; đăng nhập/đăng ký cần backend + PostgreSQL.

Profile backend `dev` có `customer@hotel-pms.test`, `staff@hotel-pms.test`, `admin@hotel-pms.test`; mật khẩu mặc định `HotelPmsDemo123!` nếu chưa đổi khi seed. Không đưa các tài khoản demo lên môi trường thật.

## Đã hoạt động / còn chờ nghiệp vụ

| Route | Trạng thái |
| --- | --- |
| `/` | Tổng quan, kiểm tra kết nối thật, điều hướng theo phiên |
| `/login`, `/register` | Gọi API Auth thật; lỗi chung + lỗi từng field; đang gửi; ẩn/hiện mật khẩu |
| `/account` | Cần đăng nhập; dữ liệu từ login/register hoặc `/auth/me` khi tải lại |
| `/rooms` | Trang khung công khai, chờ API loại phòng của Sang |
| `/booking` | Trang khung công khai, chờ tìm phòng/quote/hold của Binh và Đình Đạt |
| `/staff` | Khung bảng đặt phòng; cần STAFF hoặc ADMIN |
| `/admin` | Trang khung; cần ADMIN |
| `/ui-kit` | Tham chiếu component cho lập trình viên, không nằm trong menu sản phẩm |
| Route khác | Trang 404 |

Không có phòng, booking, doanh thu, điểm hoặc availability giả. Không gọi API nghiệp vụ chưa tồn tại. Không có luồng staff check-in/check-out. Guards ở frontend chỉ kiểm soát giao diện; backend phải bổ sung RBAC nghiệp vụ ở M1-05 trước khi mở các endpoint tương ứng.

## Cấu trúc và người tiếp tục

```text
src/
  App.tsx                    Khai báo route, bọc AuthProvider
  styles.css                 Design tokens + style chung/responsive
  shared/
    api/client.ts            HTTP, Bearer token, ApiError, details, AbortSignal
    auth/session.ts          Lưu token trong sessionStorage của tab
    layout/AppLayout.tsx     Sidebar, topbar, footer, menu mobile
    ui/index.tsx             Button, LinkButton, Badge, Card, Field,
                             Alert, PageHeader, EmptyState, LoadingState
    ui/Icon.tsx              Bộ icon SVG dùng chung
    ui/ExamplePages.tsx      UI kit, trang 404
  features/
    auth/                    Thông Đạt: form, provider, guard, account
    dashboard/               Thông Đạt: tổng quan
    rooms/                   Sang: danh mục loại phòng
    booking/                 Binh: tìm và đặt phòng
    staff/                   Thông Đạt: màn hình vận hành/quản trị
```

Đình Đạt tích hợp quote/payment với `booking`, bổ sung `features/payment` khi bắt đầu phần giao diện đó. Bảo Long duy trì hợp đồng Auth/loyalty và nền dùng chung. Cấu trúc này không thay đổi phân công trong kế hoạch gốc.

## Thêm một module

1. Chốt DTO/endpoint với backend trong `docs/api-contract.md`. Giữ tên camelCase, trạng thái booking và role khớp schema; không tự đặt tên object khác.
2. Tạo `types.ts`, `api.ts`, `XxxPage.tsx` trong `features/<module>`. Đặt HTTP call trong `api.ts`; dùng `apiRequest`, không `fetch` rải rác.
3. Bọc trang bằng `PageHeader` và `Card`. Dùng component trong `shared/ui`; tham khảo `/ui-kit`. Màu, border, radius lấy từ tokens trong `styles.css`.
4. Dữ liệu có đủ loading / error + retry / empty / success. Hủy GET khi unmount bằng `AbortController`. Không hiển thị dữ liệu giả khi API lỗi. Disable submit khi đang gửi để tránh thao tác lặp.
5. Thêm route vào `App.tsx`, thêm menu nếu cần trong `AppLayout.tsx`. Route riêng tư nằm dưới `RequireAuth`; có thể dùng `roles={['ADMIN']}`. Guest xem phòng/tìm phòng/quote được; tạo hold và thanh toán yêu cầu CUSTOMER theo quyền backend.
6. Chạy `npm run build`, kiểm tra desktop/mobile và quyền truy cập trước khi tự commit.

Ví dụ GET (thay `<endpoint-da-chot>` bằng endpoint thật, không đưa đường dẫn mẫu vào sản phẩm):

```ts
import { apiRequest } from '../../shared/api/client'
import type { RoomType } from './types'

export function getRoomTypes(signal?: AbortSignal) {
  return apiRequest<RoomType[]>('/api/v1/<endpoint-da-chot>', { signal, auth: false })
}
```

API công khai dùng `auth: false`; API riêng tư tự nhận token từ phiên. `ApiError` có `status`, `code`, `details`; map `details` vào `Field.error`, hiện lỗi chung bằng `Alert`. Tiền VND, lịch theo `Asia/Ho_Chi_Minh`; giữ offset khi gửi API. Availability, quote, discount và quyền quyết định cuối cùng đều ở backend.

## Phiên đăng nhập

- Giữ token và thời điểm hết hạn trong `sessionStorage`, không lưu mật khẩu. Phiên chỉ tồn tại trong tab hiện tại; chưa có refresh token.
- Tải lại trang gọi `/api/v1/auth/me` để lấy user/role hiện tại; không tin role từ storage hoặc tự giải mã JWT để cấp quyền.
- Hết hạn hoặc API trả 401 với token hiện tại: xóa phiên và yêu cầu đăng nhập lại. Mất mạng khi khôi phục phiên: giữ token và cung cấp nút thử lại.
- Logout xóa phiên ở client. Backend hiện chưa có thu hồi token theo thiết bị; xem `docs/auth.md`.

## Checklist kiểm tra

- `npm run build` không lỗi TypeScript.
- Guest vào `/account` hoặc `/staff` được chuyển về login; đăng nhập xong quay về route đã yêu cầu.
- Đăng nhập sai, email trùng, xác nhận mật khẩu sai: có lỗi, không mất dữ liệu form.
- Đăng ký thành công: role CUSTOMER, đến trang tài khoản; tải lại vẫn lấy đúng `/auth/me`.
- CUSTOMER không thấy menu quản lý và không mở được `/staff`/`/admin`; STAFF mở được `/staff` nhưng không `/admin`; ADMIN mở được cả hai.
- Logout / phiên hết hạn / token sai: không còn xem tài khoản riêng tư.
- Mất backend: thông báo kết nối rõ ràng, không giả báo đăng nhập thành công.
- Mobile 390px: menu mở/đóng được, form và trang không tràn ngang.
