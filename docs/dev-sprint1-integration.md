# Dev — tích hợp WIP Sprint 1

> Báo cáo lịch sử tại commit `18a047c`, trước khi sửa lỗi tích hợp. Trạng thái và hướng dẫn mới nằm trong [bàn giao ổn định Sprint 1](sprint1-stabilization.md); các kết quả FAIL bên dưới mô tả lần kiểm tra cũ.

Ngày kiểm tra: 17/10/2026. Đây là nhánh tập hợp code đã push của nhóm để tiếp tục phát triển, chưa phải bản release. Việc merge một nhánh không đồng nghĩa các task trên nhánh đó đã được nghiệm thu.

## Nhánh đã tích hợp

Nền tảng ban đầu: `origin/main` tại `3676923`. Khi bắt đầu chưa có local/remote dev; không thay đổi default branch.

| Nhánh thành viên | Commit nguồn | Nội dung |
| --- | --- | --- |
| BaoLong | c209c27 | Nền tảng, Auth/JWT, Loyalty, policy phân quyền và FE redesign |
| PhuocSang | 44174a6 | Room, RoomBlock, API quản lý phòng, cấu hình embedded PostgreSQL và seed V2 |
| NguyenDinhDat | 19ca103 | Contract và service báo giá, pricing ports, unit tests và cấu hình stub dev/CI |
| PhanThanhBinh | 4d64567 | Booking entity, API, giữ chỗ, trạng thái, scheduler và các test hiện có |
| DatHVT | 8c766f4 | Auth UI, session, điều hướng theo role, trang 403 và test frontend |

Giữ lịch sử bằng các merge commit. `main` là nhánh nền, không phải đích merge. Không có nhánh thử nghiệm nào được thêm vào.

## Cách giải quyết conflict

- `backend/pom.xml`: kết hợp dependency JWT hiện có và embedded PostgreSQL của Room; giữ version 2.2.2 đã có, không hạ xuống 2.0.7. Embedded PostgreSQL có mặt ở compile scope vì Room dùng class này trong source chính.
- `ApiException.java`: giữ `getCode()` và bổ sung alias `getErrorCode()` để handler Room dùng được cùng exception, không thay constructor.
- `SecurityConfig.java`: giữ JWT/stateless/CORS/method security của Auth, kết hợp đường dẫn thật của Room, Pricing và Booking. Không khôi phục Basic Auth/in-memory user tạm của Room. Các URL Room quản trị vẫn yêu cầu ADMIN theo nhánh Room; chưa mở STAFF cho RoomBlock. Giữ quyền confirm Booking như code nguồn để nhóm chốt sau.
- `AuditedEntity.java`: hai bản có cùng mapping và callback; giữ thêm phần chú thích của Room.
- `application-dev.yml`: giữ secret/seeder Auth và cấu hình Pricing, đồng thời hỗ trợ bật embedded DB bằng `DB_EMBEDDED=true`. Mặc định false để không thay thế database đã cấu hình của nhóm.
- `App.tsx`, `AppLayout.tsx`, `WorkspacePages.tsx`, `styles.css`: giữ các trang, font và giao diện redesign hiện có; kết hợp điều hướng Staff, đăng ký/đăng xuất, accessibility và CSS của M5. Giữ calendar và các trang quản trị hiện có.
- `AuthPage.tsx`, `RequireAuth.tsx`, `client.ts`: giữ giao diện, kết hợp validation, hủy request, session/401, return URL có query/hash, điều hướng 403 và kiểm tra dữ liệu lỗi của M5.
- `package.json`: bỏ khóa `test` trùng sau auto-merge, giữ một script chạy toàn bộ test M1 và M5, không xóa test.
- Handler lỗi Room được giới hạn trong controller Room để không tranh xử lý với handler Auth/Booking. Giữ response Room đã có; thêm xử lý 401/403 để catch-all Room không chuyển lỗi bảo mật thành 500.

Hai Pricing adapter từng được thêm trong lượt tích hợp trước đã được gỡ bằng commit tiếp theo theo yêu cầu WIP. Actor `staffId` của RoomBlock được trả lại nguyên code Sang đã push. Không hoàn thiện các task đó trong lần tích hợp này.

## Kết quả kiểm tra nhanh

| Kiểm tra | Kết quả |
| --- | --- |
| `npm ci` trong frontend | PASS, 119 packages, 0 vulnerabilities |
| `npm run build` | PASS, TypeScript và Vite build 54 modules |
| `npm test` | 35 tests: 33 passed, 2 failed, 0 skipped |
| `npm run lint` | FAIL: 23 lỗi, mô tả bên dưới |
| `mvnw.cmd -B -ntp -DskipTests compile` | FAIL tại EmbeddedPostgresConfig.java:30 |
| Backend tests | Chưa chạy được trên bản tích hợp do compile lỗi |
| Flyway runtime | Chưa kiểm tra được trên bản tích hợp do compile lỗi |
| Migration source | V1 giống byte-for-byte với main; V2 giống nhánh PhuocSang, không sửa migration |
| Git | Không còn unmerged files hoặc conflict markers; các commit nguồn đều là ancestor của dev |

Kết quả backend 100 test pass được báo trước đây thuộc riêng nhánh BaoLong, không phải kết quả của dev này.

## Lỗi và việc còn lại

### 1. Lỗi có sẵn ở nhánh Room — Sang

`backend/src/main/java/com/hotelpms/shared/config/EmbeddedPostgresConfig.java:30` gọi `pg.getPostgresDatabase("hotel_pms")` nhưng API nhận `Map<String,String>`, không nhận String. File này được giữ nguyên từ `origin/PhuocSang`. Đã đối chiếu cả thư viện 2.0.7 mà nhánh Sang dùng và 2.2.2 đang tích hợp: cả hai đều không có overload nhận String. Đây là lỗi source có trước, không phải conflict mới tạo ra. Sang cần sửa cấu hình datasource rồi kiểm tra bật embedded PostgreSQL thực tế.

### 2. Khác biệt giữa test UI và FE redesign — Thông Đạt / Long

Hai test trong `frontend/tests/routes.test.mjs` chưa khớp nội dung giao diện sau khi ghép hai nhánh:

- Dòng 38 chờ tiêu đề `Tìm và đặt phòng` tại `/booking`; giao diện redesign dùng tiêu đề khác.
- Dòng 53 chờ chữ `Đang phát triển` tại trang staff; giao diện hiện có các thông báo chưa có dữ liệu khác.

Giữ nguyên test để nhóm review lại yêu cầu hiển thị và đồng bộ, không đổi nhãn hoặc bỏ assertion chỉ để làm test xanh. Các test Auth/session, guard và 403/404 còn lại đã pass trong lần chạy này.

### 3. Lint khi ghép bộ test mới — Thông Đạt / Long

- `frontend/src/features/auth/navigation.ts:6`: rule `no-control-regex` phản đối regex kiểm tra ký tự điều khiển.
- 22 lỗi `no-undef` ở `frontend/tests/auth.test.mjs`: cấu hình ESLint hiện có chưa khai báo các global Node/browser được bộ test dùng, như Buffer, EventTarget, window, sessionStorage, Response và AbortController.

Đây là việc đồng bộ cấu hình kiểm tra sau khi tích hợp; không phải lỗi TypeScript build. Chưa tắt rule hoặc sửa logic điều hướng trong lần WIP này.

### 4. Phần nghiệp vụ đang dở — tiếp tục trên nhánh thành viên

- Room/Loyalty chưa có adapter thật cho Pricing ports. Giữ stub dev/CI của nhánh Pricing; quote stub Room trả 404, không đại diện dữ liệu phòng thật.
- RoomBlock còn hardcode actor `staffId = 1L`; cần tích hợp danh tính JWT và thống nhất quyền STAFF/ADMIN sau.
- Booking confirm hiện cho tài khoản đã đăng nhập gọi; Long, Binh và Đình Đạt cần chốt quyền và nối xác nhận thanh toán. Lần merge này không thay đổi chính sách đó.
- Các luồng tìm/đặt phòng, báo giá, thanh toán và ghi nhận Loyalty cần tiếp tục tích hợp, kiểm thử API và nối frontend theo WBS.
- Sau khi sửa compile, chạy lại toàn bộ backend tests, Flyway và kiểm thử liên module; chưa dùng dev này như bản release.

## Bảo toàn nơi làm việc ban đầu

Tích hợp trong worktree riêng. Checkout ban đầu tại `D:/GitCloneASM/Java03_Prj_HotelManagementSystem` vẫn ở BaoLong, commit c209c27. Kịch bản và các ảnh chưa commit tại đó được giữ nguyên, không stash, không đưa vào commit tích hợp. Không merge vào main, không sửa nhánh thành viên và không force push.
