# Ổn định tích hợp Sprint 1 và bàn giao Sprint 2

Ngày kiểm tra: 17/10/2026. Nhánh sửa: `fix/sprint1-integration-stabilization`, tạo từ `origin/dev` tại `18a047c6996ed90c42010317b027936e033eacef` sau khi fetch. Báo cáo lần merge trước: [dev-sprint1-integration.md](dev-sprint1-integration.md).

Stack thực tế giữ nguyên: Java release 21, Spring Boot 4.1.1, PostgreSQL 17, React/TypeScript/Vite. Không hạ về Java 17/Boot 3 theo mẫu yêu cầu vì sẽ thay đổi nền tảng đã tích hợp. Local chạy Maven bằng JDK 25.0.3 với `--release 21`; CI dùng Temurin 21.

## Kết quả kiểm tra

| Kiểm tra | Kết quả local |
| --- | --- |
| Backend compile | PASS |
| Backend verify / tests / package | PASS, 141/141 tests, 0 failed, 0 skipped; đóng gói executable JAR |
| Packaged API startup / Flyway / JPA | PASS, database `hotel_pms` mới trên PostgreSQL 17.10; migrate V1/V2, JPA validate, health UP |
| HTTP smoke trên executable JAR | PASS, 24 kiểm tra; Auth, JWT, RBAC, API Room và CORS |
| Frontend lint | PASS, 0 lỗi |
| Frontend test | PASS, 36/36, không skip |
| Frontend production build | PASS, TypeScript + Vite, 54 modules |

Lệnh cùng CI: trong backend chạy `.\mvnw.cmd -B -ntp verify`; trong frontend chạy `npm ci`, `npm run lint`, `npm test`, `npm run build`. CI Linux dùng `bash ./mvnw -B -ntp verify` và PostgreSQL service qua `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD`.

Startup local kiểm tra bằng executable JAR sau verify, bật profile dev + embedded PostgreSQL mới, API test ở port 18081. HTTP xác nhận register chỉ tạo CUSTOMER, login/me cho CUSTOMER/STAFF/ADMIN, token sai/thiếu và sai mật khẩu trả 401, quyền quản trị trả 403/200 đúng policy, RoomBlock thiếu roomId trả 400, Room API trả 5 loại phòng seed thật, CORS cho localhost:5173. Quote 404 từ fallback được kiểm tra như giới hạn WIP, không phải một báo giá thành công. Các test Auth còn kiểm tra BCrypt, token hết hạn và dữ liệu tài khoản. Đã dừng riêng API/database tạm sau smoke; không dùng hoặc sửa database local đang có. Kết quả CI trên Java 21/PostgreSQL service xem trong Checks của PR.

## Các lỗi đã sửa

| File | Nguyên nhân và cách sửa |
| --- | --- |
| `backend/src/main/java/com/hotelpms/shared/config/EmbeddedPostgresConfig.java` | API `getPostgresDatabase(String)` không tồn tại trong cả embedded-postgres 2.0.7 và 2.2.2. Lỗi source có từ nhánh Room. Dùng datasource postgres để tạo database `hotel_pms` trên cluster mới, rồi lấy `getDatabase("postgres", "hotel_pms")`. Giữ PostgreSQL và cơ chế opt-in. |
| `backend/src/main/resources/application-dev.yml` | Port 5432 cố định gây xung đột khi thành viên có PostgreSQL riêng. Embedded DB dùng port trống mặc định; cho phép chọn qua `DEV_POSTGRES_PORT`. Cấu hình datasource ngoài dev không đổi. |
| `backend/src/test/java/com/hotelpms/shared/config/EmbeddedPostgresConfigTest.java` | Thêm regression test trên PostgreSQL thật: đúng database `hotel_pms`, chạy V1/V2 và validate Flyway. |
| `backend/src/test/resources/application.properties` | Test RBAC không bật profile dev nên thiếu Pricing ports còn WIP. Bật fallback đã có chỉ trong classpath test; Room fallback vẫn trả 404, không giả dữ liệu phòng. Production vẫn fail-fast khi chưa có adapter thật. |
| `backend/src/test/java/com/hotelpms/booking/BookingIntegrationTest.java` | Fixture trùng số phòng của V2 và giả định chỉ có hai phòng trong room type đã seed. Tách ID, code và số phòng của test; giữ nguyên assertion hết phòng, room block, concurrent booking và trạng thái. Không sửa business logic Booking. |
| `backend/src/main/java/com/hotelpms/shared/web/GlobalExceptionHandler.java`, `backend/src/test/java/com/hotelpms/shared/web/RoomErrorAdviceTest.java` | Smoke HTTP phát hiện thiếu `roomId` ở API RoomBlock bị catch-all chuyển thành 500. Thêm handler tham số bắt buộc trả 400 với envelope Room hiện có và regression test; giữ tham số bắt buộc cùng quyền ADMIN. |
| `frontend/eslint.config.js` | Các test Node/browser dùng global chưa khai báo. Khai báo readonly trong phạm vi `tests/**/*.mjs`; giữ các rule. |
| `frontend/src/features/auth/navigation.ts`, `frontend/tests/auth.test.mjs` | Regex chứa control character bị lint phản đối. Tách kiểm tra ASCII điều khiển thành vòng lặp, giữ logic chống redirect ngoài site và thêm test tất cả ký tự 0x00–0x20. |
| `frontend/tests/routes.test.mjs` | Nhãn test cũ không khớp redesign đã có. Đồng bộ heading và thông báo thiếu dữ liệu, giữ kiểm tra điều hướng, vai trò, loading, 403/404 và thêm assertion luồng đặt phòng. |

Không sửa migration V1/V2, dependency version, hợp đồng API, JWT/RBAC hoặc giao diện redesign. Không tắt/skip test. Các lỗi fixture Booking chỉ lộ ra khi backend compile được sau tích hợp V2.

## Phần còn WIP cho Sprint 2

| Chủ sở hữu | Việc tiếp tục |
| --- | --- |
| Phước Sang + Bảo Long + Đình Đạt | Adapter Room/Loyalty thật cho Pricing. Dev/test/CI còn dùng fallback được bật rõ ràng; quote trả 404 từ Room stub dù room API có dữ liệu. Chưa coi báo giá tích hợp là hoàn thành. |
| Phước Sang + Bảo Long | RoomBlock còn `staffId = 1L`; cần lấy actor từ JWT và chốt quyền STAFF/ADMIN. Hiện API RoomBlock giữ ADMIN theo code nguồn. |
| Thanh Binh + Đình Đạt + Bảo Long | Chốt quyền confirm Booking và nối xác nhận thanh toán. Endpoint confirm hiện vẫn theo code nguồn: tài khoản đã đăng nhập có thể gọi. Chưa nghiệm thu luồng thanh toán hoặc dùng baseline này như bản production. |
| Bảo Long | Nối ghi nhận Loyalty vào giao dịch đã thanh toán và booking COMPLETED, bảo đảm không ghi nhận trùng. |
| Thông Đạt + chủ module | Nối API phòng, booking, quote, thanh toán và staff theo contract; giữ trạng thái thiếu dữ liệu trung thực. Auth FE hiện dùng API thật. |

V2 là migration seed hiện có của Room, gồm cả tài khoản demo và phòng mẫu; không được sửa lịch sử migration đã áp dụng. Cần xử lý seed phù hợp trước khi chuẩn bị bản production.

## Review và đồng bộ Git

`docs/contributing.md` mục 6 yêu cầu merge qua PR sau ít nhất một review. Vì vậy branch fix được push và mở PR vào `dev`; người review kiểm tra CI rồi merge. **Các lệnh lấy baseline bên dưới áp dụng sau khi PR đã merge.** Không thay đổi `main` hay nhánh cá nhân trong lần ổn định này.

Trước tiên chạy `git status`. Nếu có việc chưa commit, review và commit các file công việc trên nhánh cá nhân, hoặc tạm cất bằng `git stash push -u -m "before-sync-dev"`. Không đưa `.env`/secret vào commit. Sau khi đồng bộ, dùng `git stash pop` nếu đã stash và giải quyết conflict nếu có.

```powershell
git fetch origin --prune
git switch dev
git pull --ff-only origin dev
git switch BaoLong
git merge origin/dev
```

Thay `BaoLong` bằng nhánh của mình: `PhuocSang`, `NguyenDinhDat`, `PhanThanhBinh`, `DatHVT`. Nếu chưa có local dev, dùng `git switch --track origin/dev` thay `git switch dev`. Khi pull không fast-forward được, giữ nguyên dữ liệu và review lịch sử; không force/reset. Khi merge có conflict, sửa file và chạy kiểm tra trước commit merge. Có thể `git merge --abort` để quay lại trạng thái trước merge nếu chưa xử lý được.

Trong lúc chờ review, muốn kiểm tra riêng bản sửa thì sau khi lưu công việc dùng `git switch --track origin/fix/sprint1-integration-stabilization`. Không cần xóa nhánh cá nhân.

## Chạy local

Yêu cầu Java 21+, Node 22.12+ và mạng cho lần tải Maven/binary PostgreSQL đầu tiên. Không cần cài Maven riêng.

### PostgreSQL có lưu dữ liệu

Tại gốc repo: `docker compose up -d postgres`. Backend mặc định dùng database `hotel_pms`, user `hotel_pms` trên localhost:5432; cấu hình local xem `.env.example`. Nếu đã có PostgreSQL, tạo database và quyền phù hợp rồi đặt `DB_URL`, `DB_USER`, `DB_PASSWORD` trong terminal backend. `.env` ở gốc chỉ được Compose đọc, Java không tự đọc file này. Không chạy test vào database có dữ liệu cần giữ; test integration có thao tác dọn fixture.

Terminal backend:

```powershell
cd backend
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

### Không có Docker/PostgreSQL

```powershell
cd backend
$env:DB_EMBEDDED = 'true'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

Embedded PostgreSQL tạo cluster tạm mới mỗi lần chạy; dữ liệu không được duy trì cho lần sau. Mặc định tự chọn port PostgreSQL trống; có thể đặt `DEV_POSTGRES_PORT` nếu cần. API vẫn ở port 8080. Không đặt `DB_EMBEDDED=true` khi muốn dùng database ngoài. Nếu chạy test trong cùng terminal đã bật embedded cho dev, gỡ cờ trước: `Remove-Item Env:DB_EMBEDDED -ErrorAction SilentlyContinue`.

Terminal frontend riêng:

```powershell
cd frontend
npm ci
npm run dev
```

Mở `http://localhost:5173/login`; health API: `http://localhost:8080/actuator/health`. Vite proxy `/api` và `/actuator` sang localhost:8080.

Tài khoản profile dev: `customer@hotel-pms.test`, `staff@hotel-pms.test`, `admin@hotel-pms.test`, mật khẩu công khai dùng local: `HotelPmsDemo123!`. Seeder không ghi đè tài khoản đã tồn tại; nếu đã đổi mật khẩu dùng giá trị local của mình. `JWT_SECRET` mặc định chỉ có trong profile dev; môi trường khác cần secret riêng và adapter Pricing thật, không dùng fallback dev để triển khai production.
