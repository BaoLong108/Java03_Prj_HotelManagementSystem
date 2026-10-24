# M5-01, M5-02 — Giao diện chung, đăng ký và đăng nhập

Người phụ trách: Hồ Văn Thông Đạt. Nhánh: DatHVT → main. Nền: 3676923. Cập nhật kiểm thử: **20/10/2026 (Asia/Bangkok)**.

## Kết quả bàn giao

- **M5-01 và M5-02: đạt kiểm thử chức năng trong phạm vi Sprint 1; sẵn sàng review.** Merge vẫn cần ít nhất một review theo quy trình repo.
- API thật: **12/12 PASS** bằng PowerShell Invoke-WebRequest, không mock.
- UI thật: **13/13 PASS** bằng Playwright 1.58.2 + Edge 155.0.4283.62 headless; request đi qua Vite đến Spring Boot/PostgreSQL thật.
- npm ci: PASS, 0 vulnerabilities. npm test: **24/24 PASS**, 0 fail. npm run build: **PASS**, 43 modules.
- Maven Wrapper -B -ntp verify: **22/22 PASS**, BUILD SUCCESS, dùng PostgreSQL embedded riêng của bộ test.
- Đã xem ảnh desktop/mobile sau sửa; 5 trạng thái mobile có documentElement.scrollWidth = **375** tại viewport **375×812**.
- Chỉ chỉnh source và tài liệu/ảnh trong frontend. Không sửa backend, migration, docs chung, package-lock.json; không thêm dependency vào dự án.
- Playwright và script kiểm thử tích hợp nằm ngoài repo trong thư mục tạm hotel-pms-m5-e2e. Không commit script tạm, token hoặc mật khẩu.
- Đã dừng các tiến trình backend/frontend được khởi động cho lượt kiểm thử; cổng 8080 và 5173 không còn listener. Hai container PostgreSQL được giữ nguyên, DB của repo vẫn healthy trên 5433.

## Môi trường và cách chạy lại

Container có sẵn viral-product-radar-postgres dùng cổng 5432. Theo lựa chọn của chủ repo, giữ nguyên container đó và chạy PostgreSQL của Hotel PMS trên **5433**. Cấu hình Compose chỉ thay trong bộ nhớ, không sửa compose.yaml.

Tại gốc repo, với Docker Desktop đang chạy:
```powershell
$composeForTest = (Get-Content -Raw compose.yaml).Replace('"5432:5432"', '"5433:5432"')
$composeForTest | docker compose --project-directory $PWD.Path -f - up -d postgres --wait --wait-timeout 60
docker compose ps
```

Kết quả thực tế: java03_prj_hotelmanagementsystem-postgres-1, postgres:17-alpine, **healthy**, 5433 → 5432. Backend trả:
```json
{"groups":["liveness","readiness"],"status":"UP"}
```

Terminal backend:
```powershell
cd backend
$env:DB_URL = 'jdbc:postgresql://localhost:5433/hotel_pms'
$env:JWT_ACCESS_TOKEN_TTL_SECONDS = '60'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

TTL 60 giây chỉ đặt trong tiến trình test để kiểm tra hết hạn thật. Mặc định backend vẫn là 3600 giây; không đổi cấu hình được commit.

Terminal frontend:
```powershell
cd frontend
npm ci
npm test
npm run build
npm run dev -- --host localhost --port 5173 --strictPort
```

API: http://localhost:8080; frontend: http://localhost:5173. Vite proxy /api và /actuator sang 8080. Backend chạy bằng Java 24.0.2, frontend bằng Node 24.19.0.

## Tài khoản seed dev đã kiểm chứng

Nguồn: docs/auth.md, DemoDataSeeder, application-dev.yml và role trong V1__initial_schema.sql. Seeder không ghi đè user có sẵn. Bảng chỉ ghi email/role, không ghi mật khẩu.

| Email | Role trả từ login và /me |
| --- | --- |
| customer@hotel-pms.test | CUSTOMER |
| staff@hotel-pms.test | STAFF |
| admin@hotel-pms.test | ADMIN |

Các ca đăng ký dùng email mới sinh riêng trong DB dev. Không dùng tài khoản thật của người dùng.

## Hợp đồng Auth đã đối chiếu với response thật

Nguồn backend: commit **dcf4e8c** của Bảo Long, đã có trong nền 3676923. Không dùng hợp đồng tạm và không merge nhánh khác vào DatHVT.

- POST /api/v1/auth/register nhận email, password, fullName, phoneNumber tùy chọn; trả **201 AuthResponse có token**, tự cấp CUSTOMER.
- POST /api/v1/auth/login trả **200 AuthResponse**: accessToken (string), tokenType = Bearer, expiresIn (số giây), user = { id (number), email, fullName, roles (mảng) }.
- GET /api/v1/auth/me trả trực tiếp user, không bọc trong trường user.
- Lỗi trả { code, message, details }; details là object ánh xạ tên field sang chuỗi. Lỗi không theo field có details = {}.
- 400 VALIDATION_ERROR; 409 EMAIL_ALREADY_EXISTS; 401 INVALID_CREDENTIALS hoặc UNAUTHORIZED đúng với frontend.
- types.ts và api.ts khớp dữ liệu thực tế; **không cần sửa mapping/DTO**.
- Mật khẩu đăng ký tối thiểu 8 ký tự, tối đa 72 byte UTF-8, không trim. Email tối đa 255; họ tên tối đa 150; SĐT tùy chọn tối đa 30.
- Fallback register không token vẫn được unit test; backend thật hiện luôn trả token nên không ghi nhận fallback là đã E2E.

## Kiểm thử API thật — 12/12 PASS

Request dùng Invoke-WebRequest với JSON và Bearer token từ login. Token/mật khẩu trong ví dụ dưới đã được thay bằng dấu giữ chỗ.

| Ca | Request rút gọn | Mong đợi | Thực tế | Kết quả |
| --- | --- | --- | --- | --- |
| Đăng ký mới | POST /register: email mới, fullName, password hợp lệ, phoneNumber | 201 + token | 201, Bearer, expiresIn=60, user.roles=[CUSTOMER] | ĐẠT |
| Đăng ký trùng | POST /register với cùng email | 409 EMAIL_ALREADY_EXISTS | Đúng code, message và details={} | ĐẠT |
| Dữ liệu sai | POST /register: email=not-an-email, password=123 | 400 + lỗi field | VALIDATION_ERROR, details.email và details.password | ĐẠT |
| Login sai mật khẩu | POST /login: seed CUSTOMER + mật khẩu sai | 401 INVALID_CREDENTIALS | Đúng code, message và details={} | ĐẠT |
| Login CUSTOMER | POST /login: customer@hotel-pms.test | 200 + token | Bearer, user.id=1, roles=[CUSTOMER] | ĐẠT |
| /me CUSTOMER | GET /me + Bearer hợp lệ | 200 user trực tiếp | id=1, đúng email/role | ĐẠT |
| Login STAFF | POST /login: staff@hotel-pms.test | 200 + token | Bearer, user.id=2, roles=[STAFF] | ĐẠT |
| /me STAFF | GET /me + Bearer hợp lệ | 200 user trực tiếp | id=2, đúng email/role | ĐẠT |
| Login ADMIN | POST /login: admin@hotel-pms.test | 200 + token | Bearer, user.id=3, roles=[ADMIN] | ĐẠT |
| /me ADMIN | GET /me + Bearer hợp lệ | 200 user trực tiếp | id=3, đúng email/role | ĐẠT |
| Token sai | GET /me + Bearer invalid-token | 401 UNAUTHORIZED | Đúng code, details={} | ĐẠT |
| Thiếu token | GET /me không Authorization | 401 UNAUTHORIZED | Đúng code, details={} | ĐẠT |

Request/response tiêu biểu, rút gọn:

```text
POST /api/v1/auth/register
{"email":"<new-email>","password":"<redacted>","fullName":"M5 API Test","phoneNumber":"0900000000"}
→ 201
{"accessToken":"<redacted JWT>","tokenType":"Bearer","expiresIn":60,
 "user":{"id":4,"email":"<new-email>","fullName":"M5 API Test","roles":["CUSTOMER"]}}

POST /api/v1/auth/register (cùng email)
→ 409
{"code":"EMAIL_ALREADY_EXISTS","message":"Email đã được đăng ký","details":{}}

POST /api/v1/auth/register
{"email":"not-an-email","password":"123","fullName":"M5 Invalid"}
→ 400
{"code":"VALIDATION_ERROR","message":"Dữ liệu không hợp lệ",
 "details":{"email":"must be a well-formed email address",
 "password":"Mật khẩu cần ít nhất 8 ký tự và tối đa 72 byte UTF-8"}}

POST /api/v1/auth/login (CUSTOMER, mật khẩu sai)
→ 401
{"code":"INVALID_CREDENTIALS","message":"Email hoặc mật khẩu không đúng","details":{}}

POST /api/v1/auth/login (mỗi tài khoản seed, mật khẩu dev hợp lệ)
→ 200
{"accessToken":"<redacted JWT>","tokenType":"Bearer","expiresIn":60,
 "user":{"id":1,"email":"customer@hotel-pms.test","fullName":"Demo Customer","roles":["CUSTOMER"]}}

GET /api/v1/auth/me
Authorization: Bearer <redacted JWT>
→ 200
{"id":1,"email":"customer@hotel-pms.test","fullName":"Demo Customer","roles":["CUSTOMER"]}

GET /api/v1/auth/me (token sai hoặc không có token)
→ 401
{"code":"UNAUTHORIZED","message":"Cần đăng nhập hoặc token không hợp lệ","details":{}}
```

## Kiểm thử giao diện thật — 13/13 PASS sau sửa

| Nhóm | Hành vi đã kiểm tra | Kết quả |
| --- | --- | --- |
| a | Register 201 → tự đăng nhập; logout rồi đăng ký trùng → 409, hiện “Email đã được sử dụng”, email aria-invalid=true | ĐẠT |
| b | Login sai 401 hiện lỗi; CUSTOMER → /, header Demo Customer; mở /staff/calendar → /403 | ĐẠT |
| c | STAFF và ADMIN đều → /staff/calendar, menu nhân viên và placeholder “Đang phát triển” | ĐẠT |
| d | Cả 3 role reload gọi /me 200 và giữ đăng nhập; logout về /, xóa sessionStorage, hiện Đăng nhập/Đăng ký | ĐẠT |
| e | Home, login, register, menu mở, staff tại 375×812: scrollWidth đều 375; menu đóng bằng Escape và overlay | ĐẠT |
| Bổ sung | Form register trống: lỗi cạnh field, không gửi request | ĐẠT |
| Bổ sung | Submit login 3 lần: chỉ 1 request; nút disabled/loading; quay lại /account?view=profile#details | ĐẠT |
| Bổ sung | JWT sửa chữ ký: /me trả 401, xóa phiên, về /login | ĐẠT |
| Bổ sung | URL không tồn tại hiện 404 và nút về trang chủ hoạt động | ĐẠT |
| Bổ sung | Token thật TTL 60 giây: chờ hết hạn khi ở trang công khai → /login, thông báo hết hạn, xóa phiên | ĐẠT |

13 ca được tính gồm c chạy riêng STAFF/ADMIN và d chạy riêng CUSTOMER/STAFF/ADMIN. Không mock response API. Ca loading chỉ trì hoãn gửi request thật 500ms để quan sát trạng thái disabled/loading.

Sau sửa đã kiểm tra tự động icon đầu menu đều opacity=1, cùng tọa độ x; tiêu đề mobile có khoảng trắng “đi, một”. Đã mở và xem ảnh để kiểm tra bố cục.

### Đối chiếu A/B/C

- **A — ĐẠT:** layout guest/staff, 403/404, responsive 375px, client xử lý lỗi chung, 401 của phiên → /login.
- **B — ĐẠT:** /register, validate client, ánh xạ details theo field (unit test), email trùng (E2E thật), tự đăng nhập khi thành công.
- **C — ĐẠT:** /login, loading/chống lặp, reload, hết hạn tự logout, điều hướng role, route guard và logout.

Giới hạn bằng chứng: chưa kiểm tra Safari/Firefox hoặc thiết bị vật lý; tài khoản nhiều role và fallback đăng ký không token chỉ được unit test vì seed/API thật không cung cấp trường hợp đó. Lỗi 400 được kiểm chứng ở API thật và test ánh xạ frontend; chưa ép luồng UI tạo lỗi server 400 để chụp ảnh. Các giới hạn này không được ghi là E2E ĐẠT.

## Lỗi frontend đã sửa

Commit **7b2853d — M5-01: fix sidebar auth icons and mobile title spacing**:

1. Selector svg:last-child cũng trúng icon duy nhất của Đăng nhập/Đăng ký, làm icon bị ẩn và nội dung lệch phải. Chỉ áp dụng margin/opacity cho SVG đứng sau SVG khác; giữ icon đầu menu hiển thị và căn trái.
2. Mobile ẩn br trong tiêu đề AuthPage khiến “đi,một” dính nhau. Bổ sung khoảng trắng sau dấu phẩy.

Sau sửa: npm test 24/24, npm run build PASS; chạy lại toàn bộ 13 ca UI và chụp lại ảnh. Không phát hiện tràn ngang 375px hoặc lỗi mapping Auth cần sửa.

## Ảnh đính kèm

Ảnh gốc do Playwright chụp, không chỉnh sửa. Thư mục: frontend/docs-screenshots/M5-02/. Ảnh mobile dùng viewport 375×812; ảnh menu là đúng viewport, các ảnh còn lại chụp toàn trang.

| Ảnh | Nội dung |
| --- | --- |
| [desktop-home.png](docs-screenshots/M5-02/desktop-home.png) | Trang chủ guest, backend sẵn sàng |
| [desktop-register-duplicate.png](docs-screenshots/M5-02/desktop-register-duplicate.png) | Email trùng từ API thật |
| [desktop-register-validation.png](docs-screenshots/M5-02/desktop-register-validation.png) | Lỗi field khi bỏ trống |
| [desktop-login-error.png](docs-screenshots/M5-02/desktop-login-error.png) | Login sai từ API thật |
| [desktop-customer.png](docs-screenshots/M5-02/desktop-customer.png) | Header CUSTOMER |
| [desktop-staff.png](docs-screenshots/M5-02/desktop-staff.png) | Layout STAFF |
| [desktop-admin.png](docs-screenshots/M5-02/desktop-admin.png) | ADMIN vào khu vực staff |
| [desktop-403.png](docs-screenshots/M5-02/desktop-403.png) | CUSTOMER bị chặn |
| [desktop-404.png](docs-screenshots/M5-02/desktop-404.png) | Trang không tồn tại |
| [mobile-home.png](docs-screenshots/M5-02/mobile-home.png) | Trang chủ 375px |
| [mobile-login.png](docs-screenshots/M5-02/mobile-login.png) | Login 375px |
| [mobile-register.png](docs-screenshots/M5-02/mobile-register.png) | Register 375px |
| [mobile-menu-open.png](docs-screenshots/M5-02/mobile-menu-open.png) | Menu mở, icon căn đúng |
| [mobile-staff.png](docs-screenshots/M5-02/mobile-staff.png) | Khu vực staff 375px |

## Backend và điểm reviewer cần chú ý

- **Không phát hiện lỗi backend trong các ca đã chạy.** Không có yêu cầu sửa backend gửi Bảo Long.
- Session dùng sessionStorage theo tab; reload luôn xác minh /me trước khi cho vào route riêng tư. Cached role không cấp quyền.
- Giải mã JWT exp chỉ để hẹn giờ; backend kiểm tra chữ ký, tài khoản và quyền.
- 401 đăng nhập sai hiển thị ở form; 401 của request có xác thực xóa phiên và về login. Phản hồi cũ không được xóa phiên mới.
- STAFF/ADMIN mặc định vào /staff/calendar; phần nghiệp vụ lịch phòng/đặt phòng vẫn là placeholder đúng phạm vi Sprint 1.
- TTL 60 giây và cổng DB 5433 chỉ là cấu hình tiến trình kiểm thử, không phải thay đổi sản phẩm.

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

Bổ sung trong lượt nghiệm thu: sửa hai lỗi hiển thị nêu trên và thêm 14 ảnh trong frontend/docs-screenshots/M5-02/.
