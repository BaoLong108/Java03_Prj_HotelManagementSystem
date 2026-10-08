# Cộng tác trong repo

1. Tạo nhánh từ `main`: `feature/M2-01-room-model` hoặc `fix/M3-03-hold-race`.
2. Mỗi PR gắn task ID, mô tả thay đổi, migration/API đổi gì, cách tự kiểm tra và ảnh chụp nếu đổi UI.
3. PR thay đổi DTO, schema hoặc trạng thái booking cần review từ chủ sở hữu module phụ thuộc. Không sửa cùng một migration đã merge; tạo migration kế tiếp.
4. Trước khi xin review, chạy `backend` Maven verify và `frontend` build. CI cũng chạy hai lệnh này.
5. Merge qua PR sau ít nhất một review. Ưu tiên PR nhỏ theo một đầu ra trong file kế hoạch.

Khi module chưa có chức năng, giữ placeholder trung thực. Không mock dữ liệu như dữ liệu thật trong demo. Không commit `.env`, secret, token, `node_modules` hoặc `target`.
