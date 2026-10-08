# Phân công từ kế hoạch

| Thành viên | Khu vực chính | Task bắt đầu |
| --- | --- | --- |
| Nguyễn Ngọc Bảo Long | `backend/auth`, `backend/loyalty`, nền tảng, tích hợp | M1-01, M1-02, M1-03 |
| Lê Phước Sang | `backend/room`, `frontend/src/features/rooms` | M2-01, M2-02, M2-03 |
| Phan Thanh Binh | `backend/booking`, `frontend/src/features/booking` | M3-01, M3-02, M3-03 |
| Nguyễn Đình Đạt | `backend/pricing`, `backend/payment` | M4-01, M4-02, M4-03 |
| Hồ Văn Thông Đạt | React nền, auth UI, staff UI, dashboard | M5-01, M5-02, M5-03 |

Thứ tự phụ thuộc S1: nền tảng → schema auth/room/booking → hợp đồng quote và auth UI. S2 tập trung availability, quote, hold và thanh toán demo. S3 mở rộng staff, loyalty và VNPay. S4 kiểm thử tích hợp, bàn giao.

P0 là ưu tiên demo; P1 bổ sung nghiệp vụ; P2 làm sau khi P0/P1 ổn định. Xem file Excel kế hoạch gốc để lấy đầy đủ 42 task, người phụ trách và tiêu chí hoàn thành. Schema V1 là nền chung; các API và UI nghiệp vụ vẫn cần được triển khai.
