package com.hotelpms.pricing.port;

import java.math.BigDecimal;

/**
 * Port (giao diện) giữa module Pricing và module Room.
 *
 * <p>Mục đích: QuoteService cần biết giá phòng và sức chứa để tính báo giá,
 * nhưng không nên import trực tiếp entity RoomType của module Room.
 * Thay vào đó, Pricing định nghĩa interface này và Room sẽ implement sau.
 *
 * <p>Lợi ích:
 * <ul>
 *   <li>QuoteService có thể được viết và test hoàn toàn độc lập với module Room.</li>
 *   <li>Trong unit test, dùng mock hoặc stub thay cho implementation thật.</li>
 *   <li>Khi Lê Phước Sang hoàn thành module Room, chỉ cần tạo một class
 *       implement interface này trong package room, không cần sửa Pricing.</li>
 * </ul>
 *
 * <p><strong>Lưu ý tích hợp:</strong> Spring Boot sẽ không khởi động được nếu
 * không có bean nào implement interface này trong application context.
 * Cần Lê Phước Sang tạo adapter trong module Room trước khi chạy app thật.
 * Unit test của QuoteService không bị ảnh hưởng vì dùng mock.
 *
 * @author Nguyễn Đình Đạt
 */
public interface RoomTypePricingPort {

    /**
     * Thông tin tối thiểu về loại phòng mà Pricing cần để tính giá.
     *
     * <p>Là một nested record bên trong interface để đóng gói dữ liệu gọn gàng.
     * Chỉ chứa đúng những trường cần thiết, không expose toàn bộ RoomType entity.
     *
     * @param id                 ID của loại phòng (khớp với room_types.id trong DB)
     * @param basePricePerNight  Giá cơ bản mỗi đêm (NUMERIC 15,2 – không dùng double)
     * @param maxOccupancy       Số khách tối đa của loại phòng này
     * @param active             Loại phòng còn đang mở bán (is_active = true trong DB)
     */
    record RoomTypeInfo(
            Long id,
            BigDecimal basePricePerNight,
            int maxOccupancy,
            boolean active
    ) {}

    /**
     * Tìm thông tin loại phòng đang hoạt động theo ID.
     *
     * <p>Implementation phải:
     * <ul>
     *   <li>Tìm bản ghi trong bảng {@code room_types} có {@code id} trùng khớp.</li>
     *   <li>Nếu không tìm thấy hoặc {@code is_active = false}, ném
     *       {@link com.hotelpms.shared.api.ApiException} với HTTP 404
     *       và code {@code "ROOM_TYPE_NOT_FOUND"}.</li>
     *   <li>Không trả về {@code null}.</li>
     * </ul>
     *
     * @param roomTypeId ID loại phòng cần tìm, không được null
     * @return RoomTypeInfo chứa giá và sức chứa của loại phòng
     * @throws com.hotelpms.shared.api.ApiException HTTP 404 nếu không tìm thấy
     */
    RoomTypeInfo findActiveById(Long roomTypeId);
}
