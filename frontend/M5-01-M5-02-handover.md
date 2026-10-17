# M5-01, M5-02 — Giao diện chung, đăng ký và đăng nhập

Người phụ trách: Hồ Văn Thông Đạt. Nhánh: `DatHVT`, nền `3676923` (main tại thời điểm bắt đầu). Chỉ thay đổi trong `frontend/`; không thêm dependency. Tận dụng AuthPage, AuthProvider, RequireAuth, AppLayout, shared/ui và stylesheet đã có.

## Hợp đồng đã đối chiếu

Dùng API thật, không dùng hợp đồng tạm. Nguồn: `AuthController`, các DTO, `PasswordValidator`, `AuthService`, `ApiExceptionHandler`, `DemoDataSeeder` và `docs/auth.md` của M1-03, commit `dcf4e8c`, đã có trong nền `3676923`. Nhánh `origin/BaoLong` mới hơn được đọc để đối chiếu, không merge vào `DatHVT`.

- `POST /api/v1/auth/register`: `{ email, password, fullName, phoneNumber? }`. Trả 201 với `{ accessToken, tokenType: "Bearer", expiresIn, user: { id, email, fullName, roles } }`.
- `POST /api/v1/auth/login`: `{ email, password }`; trả 200 với cấu trúc trên.
- `GET /api/v1/auth/me`: Bearer token; trả trực tiếp `{ id, email, fullName, roles }`.
- Email bắt buộc, hợp lệ, tối đa 255 ký tự. Họ tên bắt buộc, tối đa 150 ký tự. SĐT tùy chọn, tối đa 30 ký tự; backend không có regex SĐT.
- Mật khẩu đăng ký không toàn khoảng trắng, tối thiểu 8 ký tự theo Java String.length, tối đa 72 byte UTF-8. Không trim mật khẩu. Login không áp dụng lại tối thiểu 8, nhưng chặn trống/quá 72 byte.
- 400 `VALIDATION_ERROR`: message và details theo field; 400 `MALFORMED_JSON`; 409 `EMAIL_ALREADY_EXISTS`; 401 `INVALID_CREDENTIALS`/`UNAUTHORIZED`; 403 `FORBIDDEN`.
- Backend tự cấp CUSTOMER, form không có role và không gửi role/confirmPassword. Đăng ký hiện trả token và tự đăng nhập. FE cũng hỗ trợ response chỉ có user hoặc 204: về login với thông báo `Đăng ký thành công`.

## File thay đổi

Các đường dẫn dưới đây tính từ gốc repo.

| Đường dẫn | Trạng thái | Thay đổi |
| --- | --- | --- |
| frontend/src/App.tsx | Có sẵn | Route staff lồng nhau, calendar/bookings, 403 |
| frontend/src/shared/layout/AppLayout.tsx | Có sẵn | Logo header, menu Trang chủ/Tìm phòng/auth, tên và logout, tiêu đề trang |
| frontend/src/styles.css | Có sẵn | Mở rộng CSS shell, staff nav, header/form ở màn hình hẹp |
| frontend/src/shared/ui/ExamplePages.tsx | Có sẵn | Trang 403/404 có tiêu đề và đường về trang chủ |
| frontend/src/features/staff/StaffLayout.tsx | Mới | Menu riêng cho khu vực staff |
| frontend/src/features/staff/WorkspacePages.tsx | Có sẵn | Hai placeholder trung thực “Đang phát triển” |
| frontend/src/features/auth/RequireAuth.tsx | Có sẵn | Nhớ query/hash, chuyển thiếu role về /403 |
| frontend/src/features/auth/AuthPage.tsx | Có sẵn | Validate cạnh field, khóa submit, hủy request khi rời form, email trùng, đăng ký không token |
| frontend/src/features/auth/AuthProvider.tsx | Có sẵn | Session, xác minh /me, hết hạn/401 ở mọi route, bỏ phản hồi cũ |
| frontend/src/features/auth/api.ts | Có sẵn | Giữ URL thật tập trung; AbortSignal, response đăng ký không token |
| frontend/src/features/auth/types.ts | Có sẵn | DTO, kiểu user tối thiểu và kiểm tra response lúc chạy |
| frontend/src/features/auth/navigation.ts | Mới | Điểm đến theo nhiều role, kiểm tra đường dẫn quay lại |
| frontend/src/features/auth/validation.ts | Mới | Validation theo DTO và giới hạn byte UTF-8 |
| frontend/src/shared/auth/session.ts | Có sẵn | Lưu tối thiểu, đọc hạn JWT cho timer, loại phiên lỗi/hết hạn |
| frontend/src/shared/api/client.ts | Có sẵn | Giữ message/details hợp lệ, 401 có/không token, chống 401 cũ xóa session mới |
| frontend/package.json | Có sẵn | Thêm npm test bằng Node có sẵn, không thêm dependency |
| frontend/tests/auth.entry.ts | Mới | Entry Vite cho test auth/session/client |
| frontend/tests/auth.test.mjs | Mới | 18 test validation/DTO/session/401/navigation |
| frontend/tests/routes.entry.tsx | Mới | Entry Vite cho test render route |
| frontend/tests/routes.test.mjs | Mới | 6 test menu/route/guard/form qua renderToStaticMarkup |
| frontend/M5-01-M5-02-handover.md | Mới | Đối chiếu yêu cầu, checklist và nội dung PR này |

`frontend/.env.example`, `frontend/vite.config.ts`, lockfile và các module phòng/booking/dashboard được giữ nguyên.

## Đối chiếu A/B/C

ĐẠT ở bảng này nghĩa là đã triển khai, đối chiếu mã và có kiểm thử tự động phù hợp; không thay cho kiểm thử end-to-end với backend hay kiểm tra hình ảnh trong trình duyệt.

| Tiêu chí | Kết quả | File / bằng chứng |
| --- | --- | --- |
| A: Logo, menu guest, tên + logout khi đăng nhập | ĐẠT | AppLayout.tsx; routes.test.mjs |
| A: Layout STAFF/ADMIN, calendar/bookings “Đang phát triển” | ĐẠT | StaffLayout.tsx, WorkspacePages.tsx, App.tsx; routes.test.mjs |
| A: 404 và 403 | ĐẠT | ExamplePages.tsx, RequireAuth.tsx; routes.test.mjs |
| A: Menu thu gọn, không tràn ở 375px | CHƯA ĐẠT kiểm chứng trực quan | styles.css, AppLayout.tsx; browser automation bị tắt trong Work settings |
| A: HTTP qua client, message/details, 401 xóa phiên + nhớ trang | ĐẠT | client.ts, AuthProvider.tsx, RequireAuth.tsx; auth.test.mjs |
| A: VITE_API_BASE_URL, proxy /api và /actuator | ĐẠT | client.ts; .env.example và vite.config.ts giữ nguyên |
| B: Register đúng API, xác nhận mật khẩu chỉ ở client | ĐẠT | api.ts, types.ts, AuthPage.tsx; auth.test.mjs |
| B: Required/email/password + lỗi 400 cạnh field | ĐẠT | validation.ts, AuthPage.tsx, shared/ui/index.tsx; auth.test.mjs |
| B: Email trùng hiện “Email đã được sử dụng” | ĐẠT | AuthPage.tsx xử lý 409 EMAIL_ALREADY_EXISTS |
| B: Có token tự đăng nhập; không token về login + thông báo | ĐẠT | AuthProvider.tsx, AuthPage.tsx; API fallback có test |
| B: Không chọn role, tài khoản mới CUSTOMER | ĐẠT | AuthPage.tsx + hợp đồng AuthService; routes.test.mjs |
| C: Login thật, lỗi rõ, loading, chống gửi lặp | ĐẠT | api.ts, AuthPage.tsx, client.ts |
| C: Lưu tối thiểu; reload /me; hết hạn/401 tự đăng xuất | ĐẠT | session.ts, AuthProvider.tsx; auth.test.mjs |
| C: Điều hướng nhiều role | ĐẠT | navigation.ts, AuthPage.tsx; auth.test.mjs |
| C: Guard chưa đăng nhập/thiếu role; guest xem trang chủ/tìm phòng | ĐẠT | RequireAuth.tsx, App.tsx; routes.test.mjs |
| C: Logout xóa session và về trang chủ | ĐẠT | AuthProvider.tsx, session.ts, AppLayout.tsx |
| Bảo mật: không log token, không lưu mật khẩu/SĐT/id; backend quyết định quyền | ĐẠT | Không có console/token log; lưu user chỉ fullName/email/roles; reload luôn xác minh /me |

Session dùng sessionStorage theo tab như nền mẫu: reload còn phiên, đóng tab kết thúc phiên. Cached roles chỉ là thông tin hiển thị, không cấp quyền khi khôi phục; `/me` phải thành công trước khi render route riêng tư. Giải mã `exp` chỉ để hẹn giờ hết hạn, không xác minh chữ ký ở FE. Backend kiểm tra chữ ký, tài khoản và quyền.

## Tự kiểm tra đã chạy

- `npm ci`: PASS, 27 packages, 0 vulnerabilities; lockfile không đổi.
- `npm run build`: PASS (`tsc && vite build`), 43 modules; không lỗi TypeScript.
- `npm test`: PASS 24/24 test, 0 fail. Test API thay fetch trong tiến trình Node; test route dùng context riêng trong bundle test, không được đưa vào sản phẩm.
- `git diff --check`: PASS.
- Chỉ có thay đổi frontend. Không chạy Maven hoặc sửa backend/migration/docs gốc.
- Chưa chạy E2E với Spring/PostgreSQL và chưa chụp UI desktop/mobile. Công cụ trình duyệt báo `Local browser use is disabled in Work settings`; không coi SSR là kiểm tra pixel/overflow.

## Checklist test tay

Chuẩn bị backend trên nền đã có M1-03 và PostgreSQL theo README gốc; bật profile `dev`. Chạy frontend `npm ci`, `npm run dev`. Tài khoản lấy từ `DemoDataSeeder`:

| Vai trò | Email | Mật khẩu mặc định dev |
| --- | --- | --- |
| CUSTOMER | customer@hotel-pms.test | HotelPmsDemo123! |
| STAFF | staff@hotel-pms.test | HotelPmsDemo123! |
| ADMIN | admin@hotel-pms.test | HotelPmsDemo123! |

`DEMO_SEED_PASSWORD` có thể đã được đổi trước lần seed đầu; seeder không ghi đè user có sẵn. Không có seed nhiều role, cần dùng tài khoản đã được backend cấp nhiều role nếu kiểm thử bằng API thật.

- [ ] **Đăng ký mới:** /register → nhập họ tên, email chưa dùng, mật khẩu hợp lệ + nhập lại; SĐT có thể bỏ trống → bấm Tạo tài khoản. Mong đợi POST đúng DTO, 201, tự vào `/`, header hiện tên, `/account` có CUSTOMER. Request không có roles/confirmPassword.
- [ ] **Validation:** để trống; email sai; mật khẩu <8; 37 ký tự `á` (=74 byte); mật khẩu nhập lại lệch → lỗi tại field, không gửi API. 36 ký tự `á` (=72 byte) được chấp nhận. Lỗi 400 từ backend hiện message chung và details cạnh field.
- [ ] **Trùng email:** đăng xuất, đăng ký bằng email vừa dùng (thử cả khác chữ hoa/thường) → 409; hiện chính xác “Email đã được sử dụng”, email có lỗi, không tạo session.
- [ ] **Sai login:** dùng email seed + mật khẩu sai → 401 INVALID_CREDENTIALS, hiện “Email hoặc mật khẩu không đúng”, vẫn ở form. Click nhiều lần/Enter liên tiếp khi mạng chậm → chỉ một request đang xử lý, nút loading/disabled.
- [ ] **CUSTOMER:** login customer → `/`, thấy tên và logout, không thấy menu quản lý.
- [ ] **STAFF:** login staff → `/staff/calendar`; mở `/staff/bookings` được, cả hai có “Đang phát triển”; `/admin` → `/403`.
- [ ] **ADMIN:** login admin → `/staff/calendar`; hai trang staff và /admin được mở. Tài khoản CUSTOMER+STAFF hoặc CUSTOMER+ADMIN cũng vào staff.
- [ ] **Nhớ trang:** guest mở `/account?view=profile#details` → login → đăng nhập CUSTOMER → quay đúng pathname/query/hash. Guest mở `/staff/bookings?q=demo#results` → login STAFF → quay đúng route staff đó.
- [ ] **Reload:** đang login, F5 tại /account hoặc staff → gọi /me, vẫn đúng user/role. Sửa cached roles không cấp quyền; giá trị /me quyết định. Khi tắt backend, route riêng tư hiện lỗi xác thực và nút Thử lại.
- [ ] **Logout:** bấm Đăng xuất → về `/`, storage key `hotel-pms.session.v1` bị xóa; Back vào route riêng tư → login.
- [ ] **CUSTOMER vào staff:** dán `/staff`, `/staff/calendar` hoặc `/staff/bookings` → `/403` với “Không đủ quyền”; không thấy nội dung staff.
- [ ] **Hết hạn/không hợp lệ:** dùng TTL dev ngắn hoặc thay token bằng chuỗi không hợp lệ rồi reload → xóa phiên, login, giữ trang quay lại. Token có cấu trúc hợp lệ nhưng chữ ký sai phải bị /me trả 401. Thử hết hạn khi đang ở trang công khai.
- [ ] **Backend ngừng chạy:** login/register → lỗi kết nối/HTTP trung thực, không báo thành công, nút dùng lại được.
- [ ] **Fallback register:** trong môi trường kiểm thử cô lập, response thành công chỉ có user hoặc 204 → /login hiện “Đăng ký thành công”, không tự tạo token. Backend hiện tại luôn trả token.
- [ ] **Mobile:** viewport 375×812 → trang chủ, /login, /register, /staff/bookings; không có thanh cuộn ngang, label/error/input không tràn; menu Mở/Đóng, Escape và overlay hoạt động; tên dài không đẩy nút Đăng xuất ra ngoài.
- [ ] **404:** mở đường dẫn không tồn tại → trang 404, nút Về trang chủ hoạt động.

## Mô tả PR sẵn để dán

**Tiêu đề:** M5-01, M5-02: Hoàn thiện giao diện chung, đăng ký và đăng nhập

**Task / người thực hiện:** M5-01 + M5-02 — Hồ Văn Thông Đạt. Nhánh `DatHVT` → `main`.

Hoàn thiện frontend mẫu hiện có: menu guest có Trang chủ/Tìm phòng/Đăng nhập/Đăng ký, tên và logout khi có session; layout STAFF/ADMIN với calendar/bookings “Đang phát triển”; trang 403/404 và menu responsive.

Đăng ký/đăng nhập dùng đúng API M1-03. Bổ sung validation cạnh field, giới hạn mật khẩu 72 byte UTF-8, thông báo email trùng, chống submit lặp và fallback đăng ký không trả token. Session chỉ lưu token/hạn dùng/tên/email/roles theo tab; reload xác minh /me; hết hạn hoặc 401 xóa phiên và nhớ trang quay lại. Điều hướng hỗ trợ nhiều role; phản hồi request cũ không ghi đè/xóa phiên mới.

Phạm vi chỉ frontend, không đổi API/schema/migration, không thêm thư viện. Menu/guard FE phục vụ trải nghiệm; backend vẫn quyết định quyền.

**Đã kiểm tra:** `npm ci`, `npm run build`, `npm test` (24/24), `git diff --check`. Bộ test dùng response giả lập có kiểm soát trong test, sản phẩm không có mock login.

**Cần kiểm tra trước merge:** chạy checklist trong `frontend/M5-01-M5-02-handover.md` với backend thật; xác nhận màn hình 375px. Chưa chạy E2E và chưa chụp ảnh do browser automation bị tắt ở môi trường thực hiện.

**Ảnh cần chụp:** trang chủ guest desktop; đăng ký với lỗi field/trùng email; đăng nhập sai; header đã login; staff calendar/bookings; trang 403 của CUSTOMER; menu mobile mở và form đăng ký ở 375px.

## Điểm cần theo dõi

Không còn câu hỏi về hợp đồng Auth. Không có thay đổi backend cần quyết định. Cần xác nhận checklist E2E/mobile trước merge và lấy ít nhất một review theo quy trình repo. Không có seed nhiều role; tests tự động đã bao phủ mảng role, còn test tay nhiều role cần tài khoản do backend cấp sẵn. Nhánh lịch phòng M5-04 riêng không bị sửa/merge vào nhánh này.
