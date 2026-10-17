# Cộng tác trong repo

1. Sprint 2 tạo nhánh mới từ `dev` sau khi PR ổn định Sprint 1 được review và merge: `feature/M2-01-room-model` hoặc `fix/M3-03-hold-race`. Nhánh cá nhân đang dùng có thể merge `origin/dev`; xem `docs/sprint1-stabilization.md`.
2. Trước khi tạo entity/repository, đọc `docs/database.md` và mapping đúng tên bảng, cột, kiểu dữ liệu trong migration V1. Không dùng `ddl-auto=update` hoặc tự đặt tên khác cho cùng khái niệm.
3. Mỗi PR gắn task ID, mô tả thay đổi, migration/API đổi gì, cách tự kiểm tra và ảnh chụp nếu đổi UI.
4. PR thay đổi DTO, schema hoặc trạng thái booking cần review từ chủ sở hữu module phụ thuộc. Không sửa cùng một migration đã merge; tạo migration kế tiếp.
5. Trước khi xin review, chạy `backend` Maven verify và `frontend` build. CI còn khởi động PostgreSQL để kiểm tra Flyway.
6. Merge qua PR sau ít nhất một review. Ưu tiên PR nhỏ theo một đầu ra trong file kế hoạch.

Khi module chưa có chức năng, giữ placeholder trung thực. Không mock dữ liệu như dữ liệu thật trong demo. Không commit `.env`, secret, token, `node_modules` hoặc `target`.
