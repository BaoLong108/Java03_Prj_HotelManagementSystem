# Hotel PMS Mini

Monorepo cho đồ án quản lý một khách sạn. Nguyễn Ngọc Bảo Long phụ trách kỹ thuật và tích hợp. Kế hoạch chia 4 sprint, 5 thành viên; ưu tiên hoàn thành các việc P0 trước.

## Cấu trúc

```text
backend/                 Spring Boot API, Java 21, PostgreSQL, Flyway
  src/main/java/com/hotelpms/
    auth/                Tài khoản, role, customer profile, JWT
    room/                RoomType, PhysicalRoom, RoomBlock, availability
    booking/             Booking, hold, trạng thái và job
    pricing/             Quote, giá và discount snapshot
    payment/             Thanh toán demo, sau đó VNPay Sandbox
    loyalty/             Điểm và hạng khách hàng
    shared/              Cấu hình và thành phần dùng chung
  src/main/resources/db/migration/  Migration SQL theo thứ tự Flyway
frontend/                React + TypeScript + Vite
  src/features/           Mã giao diện theo nghiệp vụ
  src/shared/api/         API client dùng chung
docs/                    Quy ước kiến trúc, API, cộng tác
compose.yaml             PostgreSQL cho local
```

Backend đã có entity tài khoản, đăng ký/đăng nhập JWT và `/api/v1/auth/me`. Xem [hợp đồng API Auth](docs/auth.md) để frontend tích hợp. Các màn hình hiện là placeholder; các module đặt phòng/thanh toán và RBAC theo nghiệp vụ sẽ được triển khai ở task tiếp theo.

## Chạy local

Yêu cầu: Java 21+, Node.js 22.12+, Docker Compose. Repo có Maven Wrapper nên không cần cài Maven.

1. Sao chép `.env.example` thành `.env` nếu muốn đổi cấu hình PostgreSQL. Giá trị mặc định chỉ dành cho local.
2. Chạy `docker compose up -d postgres` tại gốc repo.
3. Chạy backend: `cd backend` rồi `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (PowerShell dùng `.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'`). API ở `http://localhost:8080/actuator/health`.
4. Chạy frontend ở terminal khác: `cd frontend`, `npm ci`, `npm run dev`. Mở địa chỉ Vite in ra, thường là `http://localhost:5173`.

Backend mặc định dùng `jdbc:postgresql://localhost:5432/hotel_pms`, user `hotel_pms`, password `hotel_pms_dev`. Có thể đổi qua `DB_URL`, `DB_USER`, `DB_PASSWORD`. File `.env` ở gốc dành cho Compose; backend đọc biến môi trường của terminal chạy Java. Frontend dev server proxy `/api` và `/actuator` sang backend. Khi dùng API ở host khác, đặt `VITE_API_BASE_URL` trong `frontend/.env.local`.

Profile `dev` tạo ba tài khoản: `customer@hotel-pms.test`, `staff@hotel-pms.test`, `admin@hotel-pms.test`; mật khẩu demo chung là `HotelPmsDemo123!`. Có thể đổi bằng `DEMO_SEED_PASSWORD` trước lần seed đầu tiên, hoặc tắt bằng `DEMO_SEED_ENABLED=false`. Seeder không ghi đè tài khoản đã tồn tại. Ngoài `dev`, phải cung cấp `JWT_SECRET` ngẫu nhiên dài ít nhất 32 ký tự; không có mật khẩu hay signing key mặc định cho môi trường đó.

## Kiểm tra trước PR

```text
backend:  ./mvnw verify       (Windows: .\mvnw.cmd verify)
frontend: npm ci && npm run build
```

Backend test dùng PostgreSQL 17 tạm thời qua embedded-postgres, tự khởi động và dừng nên không cần Docker để chạy test. CI dùng PostgreSQL service qua `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD`. Các test kiểm tra migration, entity, seed, đăng ký/đăng nhập, email trùng đồng thời và token sai/hết hạn. Cần mạng ở lần đầu để Maven tải thư viện và binary PostgreSQL.

Xem [schema và quy ước đặt tên DB](docs/database.md), [kiến trúc](docs/architecture.md), [hợp đồng API](docs/api-contract.md), [phân công](docs/ownership.md) và [quy trình cộng tác](docs/contributing.md) trước khi bắt đầu task. File kế hoạch Excel là nguồn phân công và tiêu chí hoàn thành; các file `docs/` ghi quyết định kỹ thuật để code giữa các module khớp nhau.

