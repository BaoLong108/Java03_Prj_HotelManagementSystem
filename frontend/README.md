# Frontend Hotel PMS Mini

Frontend dùng React + TypeScript + React Router + Vite với hai bố cục: website khách và workspace Staff/Admin. Đây là nền chung để nhóm phát triển module theo cùng component, design tokens và cách gọi API.

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
| `/` | Hero ảnh concept, chọn ngày/số khách, giới thiệu và chính sách thành viên |
| `/login`, `/register` | Gọi API Auth thật; lỗi chung + lỗi từng field; đang gửi; ẩn/hiện mật khẩu |
| `/account` | Cần đăng nhập; dữ liệu từ login/register hoặc `/auth/me` khi tải lại |
| `/rooms` | Tìm phòng/quote từ API thật, chọn ngày/số khách, URL chia sẻ được, đăng nhập quay lại lịch đã chọn |
| `/rooms/:roomTypeId` | Trạng thái chưa sẵn sàng; chờ API chi tiết, ảnh và tiện nghi |
| `/booking` | Quote → hold 15 phút → thanh toán demo, snapshot server, tải PDF xác nhận |
| `/my-bookings` | CUSTOMER; danh sách booking thật, tiếp tục thanh toán hold còn hạn, hủy booking chưa trả tiền |
| `/staff` | Dashboard, health thật, điều hướng công cụ; KPI/chart chờ Analytics API; STAFF hoặc ADMIN |
| `/staff/bookings` | Tra mã booking/SĐT đầy đủ qua API STAFF/ADMIN, contact đã che, tải PDF |
| `/staff/calendar` | Lịch phòng vật lý 7 ngày, booking + block thật, lọc loại phòng và chi tiết |
| `/staff/rooms` | Trạng thái chưa sẵn sàng; không suy ra phòng trống/dọn sạch |
| `/admin` | Điều hướng các khu vực quản trị; cần ADMIN |
| `/admin/rooms` | Chuyển tab loại phòng/phòng vật lý/giá; chờ CRUD API |
| `/admin/members` | Chính sách thành viên; thống kê chờ API |
| `/admin/settings` | Quy tắc lưu trú tham chiếu; quản trị nhân viên chờ API |
| `/ui-kit` | Tham chiếu component cho lập trình viên, không nằm trong menu sản phẩm |
| Route khác | Trang 404 |

Mặc định gọi API thật. `VITE_USE_FIXTURES=true` chỉ bật dữ liệu mẫu Staff có banner, không dùng để nghiệm thu nghiệp vụ. Không có luồng staff check-in/check-out. Guards ở frontend kiểm soát giao diện; backend đã kiểm tra JWT/RBAC và ownership. Contract hiện tại nằm trong [Sprint 2 completion](../docs/sprint2-completion.md).

## Cấu trúc và người tiếp tục

```text
src/
  App.tsx                    Khai báo route, bọc AuthProvider
  styles.css                 Design tokens + style chung/responsive
  shared/
    api/client.ts            HTTP, Bearer token, ApiError, details, AbortSignal
    auth/session.ts          Lưu token trong sessionStorage của tab
    layout/AppLayout.tsx     Guest navbar/footer, Staff sidebar/header,
                             AccountMenu, tìm công cụ, RouteEffects
    ui/index.tsx             Button, LinkButton, Badge, Card, Field,
                             Alert, PageHeader, EmptyState, LoadingState
    ui/Icon.tsx              Bộ icon SVG dùng chung
    ui/ExamplePages.tsx      UI kit, trang 404
    ui/primitives.tsx        Select, Tabs, Skeleton, PriceDisplay, DataTable,
                             Pagination
    ui/Feedback.tsx          Dialog, ConfirmDialog, ToastProvider/useToast
    api/health.ts            Hook health thật, AbortController và retry
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
6. Chạy `npm run lint`, `npm test`, `npm run build`; kiểm tra desktop/mobile và quyền truy cập trước khi tự commit.

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

## Design system và component nghiệp vụ

- Tokens navy, off-white, champagne ở `styles.css`; không định nghĩa lại màu/spacing theo từng module. Tiêu đề trải nghiệm dùng Noto Serif có đủ ký tự tiếng Việt (cả chữ nghiêng), controls/workspace dùng Segoe UI/system. Font WOFF2 nằm trong `src/assets/fonts`, khai báo trong `src/fonts.css`; không tải font hoặc ảnh từ bên thứ ba khi mở web. Giữ các subset Vietnamese/Latin Extended/Latin và giấy phép OFL đi kèm khi đổi font.
- `Dialog` dùng HTML dialog, focus trong modal, Escape, backdrop, trả focus về nút mở và khóa cuộn. `ConfirmDialog` dùng lại Dialog. Toast tự tắt sau 5 giây, có nút đóng và cleanup timer.
- `Field type="date"` là date picker gốc của trình duyệt. `StaySearchForm` kiểm tra ngày Việt Nam, ngày đi > ngày đến, điều chỉnh ngày đi khi đến thay đổi, người lớn/trẻ em, giữ tiêu chí trong URL. Sức chứa/chính sách trẻ em phải lấy từ API; giới hạn chọn hiện tại chỉ là giới hạn UI (1–10 người lớn, 0–10 trẻ em).
- `features/rooms/RoomCard.tsx`: RoomCard + ImageGallery; chỉ nhận props trình bày. `RoomPresentation` **chưa phải DTO REST đã chốt**. Map từ response thật sau khi thống nhất với Sang; không gọi endpoint mẫu.
- `features/booking/BookingComponents.tsx`: BookingCard, BookingStatusBadge, BookingPriceSummary, HoldCountdown. Tất cả giá nhận từ API, `discountRate` theo tỷ lệ thập phân V1 (0.1 = 10%); không tự tính `payableAmount`.
- HoldCountdown nhận thời hạn ISO có offset từ backend, tính lại theo đồng hồ khi tab quay lại, cleanup interval, gọi `onElapsed` một lần cho mỗi booking/thời hạn. Parent phải khóa thanh toán, tải lại booking và xác nhận trạng thái từ máy chủ; component không tự chuyển booking thành EXPIRED, không gia hạn hold trên reload. Hiện chỉ được minh họa tại UI kit vì API hold chưa tồn tại.
- Ví dụ room, booking, báo giá và bộ đếm chỉ có ở `/ui-kit`, có nhãn minh họa rõ; không xuất hiện như dữ liệu thật trên portal.
- Chưa thêm dark mode hoặc chart library vì chưa có dữ liệu/nhu cầu triển khai tương ứng. Icon SVG dùng một kiểu nét thống nhất.

## Kiểm tra tự động

Yêu cầu Node 22.13+ (khuyến nghị bản Node 22 LTS mới nhất; cùng dòng với CI).

```powershell
npm ci
npm run lint
npm test
npm run build
```

ESLint kiểm tra TypeScript và unused imports. Test dùng Node test runner, không thêm runtime dependency: ngày Việt Nam, lịch lỗi, rollover, khoảng đêm, URL nhập tay, guest bounds, round-trip tiêu chí, deadline có offset và hold hết hạn sau khi tab nghỉ. Node 22 cũ có thể in cảnh báo experimental type stripping; test vẫn chạy. CI frontend chạy cả build, lint và test.

Nhật ký audit, file thay đổi, kiểm tra trình duyệt và những API còn thiếu: [docs/frontend-redesign.md](../docs/frontend-redesign.md).
