# Frontend redesign — 15/10/2026

## Phase 1 — Audit source

- React 19, TypeScript 6, Vite 8, React Router 7. Không có UI framework, thư viện chart, state store, lint hoặc frontend test runner.
- Giữ các thư mục `features`, `shared`, `assets`; tiếp tục AuthContext, centralized `apiRequest` và sessionStorage theo tab.
- Routes cũ: `/`, `/rooms`, `/booking`, `/login`, `/register`, `/account`, `/staff`, `/admin`, `/ui-kit` và 404.
- Component tái sử dụng: Button, LinkButton, Field, Card, Badge, Alert, EmptyState, LoadingState, PageHeader, Icon.
- Hoạt động: đăng ký, đăng nhập, `/auth/me`, guard theo role, expiry/401, lỗi mạng có retry, health check. Không có refresh-token endpoint; không triển khai giả refresh token.
- Chỉ có controller Auth và Actuator health. Room/Booking/Pricing/Payment/Loyalty/Analytics chưa có controller, DTO response hoặc endpoint để tích hợp.
- `/auth/me` trả id, email, fullName, roles; không trả tier, points, cumulativeSpent. Không suy ra hạng Standard hoặc số dư 0 từ dữ liệu thiếu.
- Backend role thực tế: CUSTOMER / STAFF / ADMIN. Guest ẩn danh. Chưa có RECEPTIONIST/HOUSEKEEPING hoặc room states CLEAN/DIRTY/CLEANING/INSPECTED.
- Ranh giới mini hiện tại không có check-in/out, đổi phòng, housekeeping, folio. Yêu cầu redesign không tự tạo backend cho những nghiệp vụ này. Phải có migration, RBAC, state machine và API thống nhất trước khi bật thao tác.
- UI cũ dùng chung sidebar cho cả khách và nhân viên; trang khách thiếu visual hierarchy, tìm ngày, membership policy, booking history; các trang nghiệp vụ là empty state.

## Tham khảo UI/UX

Khảo sát ngày 15/10/2026, chỉ dùng ý tưởng bố cục và tương tác, không lấy logo/ảnh/copy của các thương hiệu:

- [Hilton](https://www.hilton.com/en/): ngày và số khách gần CTA, tài khoản và stays dễ tìm, phần membership tách rõ.
- [Marriott](https://www.marriott.com/): nội dung hình ảnh lớn, typography có phân cấp, benefits thành viên và nội dung lưu trú.
- [Airbnb](https://www.airbnb.com/), [Booking.com](https://www.booking.com/), [Agoda](https://www.agoda.com/): truy cập tham khảo; nội dung động/challenge giới hạn việc đọc đầy đủ qua công cụ. Không khẳng định đã kiểm tra checkout có đăng nhập.
- [Mews](https://www.mews.com/en), [Little Hotelier](https://www.littlehotelier.com/): nhóm công cụ vận hành theo công việc và lịch đặt phòng.
- [Cloudbeds PMS](https://www.cloudbeds.com/property-management-system/), [hướng dẫn dashboard](https://myfrontdesk.cloudbeds.com/hc/en-us/articles/115000400634-Dashboard-Everything-you-need-to-know): tổng quan, danh sách đặt chỗ và trạng thái dễ quét. Homepage Cloudbeds lỗi truy cập, dùng trang sản phẩm/hướng dẫn chính thức thay thế. Không truy cập dashboard nội bộ có đăng nhập của đối thủ.

## Quyết định thiết kế

- Guest: header ngang, hero ảnh concept nguyên bản, navy / off-white / champagne, serif cho tiêu đề trải nghiệm; nội dung tiếng Việt.
- Staff/Admin: sidebar thu gọn được, navigation search, breadcrumb, profile menu, card/table nhất quán.
- Khoảng cách theo 4/8 px, focus rõ, controls tối thiểu 40 px, transition 180 ms, reduced motion.
- Các trang chưa có API hiển thị trạng thái chưa sẵn sàng. Không hiển thị giá/room/KPI/chart/history giả, không bịa endpoint, không cung cấp thao tác ghi dữ liệu chưa có backend.

## Tiến độ triển khai và kiểm tra

- Phase 1: đã đọc frontend, controller/security, hợp đồng API và kiến trúc; xác nhận phạm vi như trên.
- Phase 2: hoàn thành tokens/CSS, SVG icons và shared Button/Field/Select/Tabs/Card/Badge/Table/Pagination/PriceDisplay/Spinner/Skeleton/Alert/EmptyState/Dialog/ConfirmDialog/Toast. Files: `frontend/src/styles.css`, `shared/ui/*`. Không thêm UI runtime library.
- Phase 3: hoàn thành Guest layout, homepage, form ngày/số khách, auth/account, membership policy, summary booking 4 bước và trang trạng thái danh mục/chi tiết/lịch sử. Files: `shared/layout/AppLayout.tsx`, `features/dashboard/DashboardPage.tsx`, `features/rooms/*`, `features/booking/*`, `features/auth/AccountPage.tsx`, `AuthPage.tsx`, `MembershipBenefits.tsx`.
- Phase 4: hoàn thành Staff/Admin layout, role menu, thu gọn sidebar, search công cụ, dashboard có health thật, các trang đặt phòng/lịch/phòng và tab danh mục/chính sách/cài đặt. File: `features/staff/WorkspacePages.tsx`, routes trong `App.tsx`. Tất cả dữ liệu chưa có API được ghi rõ chưa sẵn sàng, không giả KPI hoặc số lượng booking bằng 0.
- Phase 5: hoàn thành lint/build/test và QA browser ngày 16/10/2026 (xem dưới). Không thay backend hoặc DTO Auth. Thêm fallback tiếng Việt cho HTTP 400/401/403/404/409/422/500 trong centralized client. Thêm lint/test vào CI frontend; GitHub chưa chạy bản thay đổi này vì chưa commit/push.

## Kết quả kiểm tra

- `npm run lint` (ESLint 10 + typescript-eslint), `npm run build`: thành công. `npm test`: 11 test qua. Không thêm thư viện runtime; thêm tooling lint ở devDependencies và dùng Node test runner.
- `backend/mvnw.cmd -B -ntp verify`: 22 AuthIntegrationTest qua, 0 lỗi (15/10/2026). Backend source không đổi trong redesign.
- Browser API thật: login sai trả lỗi; login Customer thành công; reload khôi phục `/auth/me`; đăng ký trả CUSTOMER; mismatch password bị chặn; email trùng hiện lỗi 409; logout cancel/confirm và toast hoạt động.
- RBAC: Customer bị chặn `/staff`, không có menu quản lý; Staff có 4 mục vận hành, bị chặn `/admin`; Admin mở các trang quản trị. Frontend không cấp quyền từ JWT tự giải mã.
- Mô phỏng riêng request `/auth/me` lỗi mạng: trang giữ phiên, có retry; bỏ chặn API và retry khôi phục tài khoản Admin không cần login lại. Các thiết lập thử mạng đã được gỡ trên tab kiểm tra cuối.
- Search: ngày/số khách đi vào URL, tính số đêm để trình bày; đã sửa submit lấy giá trị hiển thị của date inputs, bảo đảm browser date autofill không gửi state cũ. Test 20–23/10 với 2 người lớn/1 trẻ em ra đúng URL và 3 đêm.
- Responsive: xem trực tiếp homepage desktop 1440, listing/account/calendar mobile 375, các trang staff/admin ở 375/768/1024; không tràn ngang trang. Bảng có vùng cuộn riêng. Có menu mobile, Escape trả focus, backdrop; gallery modal và pagination đã thử tại UI kit.
- Sidebar thu gọn, tìm công cụ → lịch, chuyển tuần, tab phòng vật lý và đổi cột bảng hoạt động. Đã sửa heading Auth thành h1 để mobile có tiêu đề chính khi ảnh/story ẩn.
- FE cũ và backend dev được khởi động lại phục vụ preview. Không commit/push thay đổi.

## Các phần chưa thể hoàn tất khi chưa có backend

| Phần | Cần tích hợp tiếp | Chủ module theo kế hoạch |
| --- | --- | --- |
| Room listing/detail | Danh mục, ảnh có quyền sử dụng, tiện nghi, filter/sort/pagination và availability theo khoảng lưu trú | Sang |
| Booking | Quote, hold, trạng thái, cancel, lịch sử riêng tư và payment demo/VNPay | Binh + Đình Đạt |
| Membership | Profile có tier/spend/points, progress theo dữ liệu thật và award history | Bảo Long |
| Staff/Admin | Booking search/filter/detail, timeline room/block, room CRUD, rates, employee management | Thông Đạt + Sang + các chủ BE |
| Analytics/invoice | KPI/chart, PDF/email, điều kiện invoice và tải chứng từ được cấp quyền | Thông Đạt + Đình Đạt |
| Refresh token | Endpoint/quy trình rotation, expiry và revocation; hiện chỉ có access token | Bảo Long |
| Check-in/out, room move, housekeeping, folio | Ngoài phạm vi mini gốc; cần mở rộng schema, role/quyền, state machine và API trước | Cả nhóm thống nhất phạm vi |

RoomCard/BookingCard/ImageGallery/BookingPriceSummary/HoldCountdown đã có dưới dạng component nhận props; chưa nối vào API nghiệp vụ hoặc mở thanh toán. Không tạo endpoint phỏng đoán để làm giao diện có vẻ đã tích hợp.

## Ảnh sử dụng

- Asset: `frontend/src/assets/hotel-courtyard.webp` (~359 KB), tạo bằng công cụ Imagegen tích hợp; không dùng ảnh lấy từ các website tham khảo. Chú thích ảnh concept trong giao diện.
- Prompt cuối: “Use case: photorealistic-natural. Asset type: original wide website hero for a modern luxury boutique hotel concept, 16:9. Architectural editorial photography of a serene courtyard hotel with travertine arches, warm teak details, soft cream linen lounge chairs, one reflective pool and a lush tropical tree. Large architectural forms, intimate boutique scale, sophisticated and believable construction, no recognizable existing hotel. Warm late-afternoon sun casts long shadows across textured stone, muted olive foliage, warm white and champagne tones. Wide balanced composition with the right two thirds containing beautiful hotel architecture and left third darker shaded foliage suitable for white text overlay. High quality camera realism, calm luxurious atmosphere. No people, no logos, no lettering, no watermark. This is a concept illustration for a software project, not a photograph documenting a real property.”
- PNG gốc giữ trong thư mục generated_images của Codex; bản WebP trong repo là tài sản dự án sử dụng trực tiếp.

## Sửa font tiếng Việt — 16/10/2026

- Tiêu đề “Mọi điều cần thiết, rõ ràng hơn.” bị trộn Georgia và Times New Roman cho các glyph tiếng Việt; xác nhận bằng `CSS.getPlatformFontsForNode` trong trình duyệt. Nội dung UTF-8 trong source vẫn đúng.
- Thay token `--serif` bằng Noto Serif tự lưu trong dự án, đủ Vietnamese/Latin Extended/Latin, weight 400–600 và italic thật. Khai báo dùng chung trong `src/fonts.css`, import từ `main.tsx`; giữ giấy phép SIL OFL và nguồn tải trong `src/assets/fonts`. Không thêm dependency hoặc request CDN lúc người dùng mở web.
- Bỏ tracking âm mạnh ở hero và tiêu đề section để dấu và khoảng cách chữ rõ ràng. Áp dụng cho mọi trang dùng token, không sửa riêng một câu.
- Kiểm tra lại font thực tế của hero thường/nghiêng, tiêu đề section/thành viên/phòng và footer: chỉ còn Noto Serif; body dùng Segoe UI. Kiểm tra desktop và mobile 375px, form/heading không tràn ngang; lint và build thành công.
