package com.hotelpms.pricing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;

/**
 * DTO chứa tham số đầu vào cho yêu cầu báo giá phòng.
 *
 * <p>Được nhận qua query string của GET request:
 * {@code GET /api/v1/pricing/quote?roomTypeId=1&startAt=...&endAt=...&guestCount=2}
 *
 * <p><strong>Lưu ý về customerId:</strong> Trường này KHÔNG có trong request.
 * Nếu khách đã đăng nhập, Controller lấy userId từ JWT token (do server ký) và
 * truyền vào QuoteService. Điều này ngăn client tự khai báo ID để được giảm giá
 * cao hơn tier thực tế của mình.
 *
 * <p><strong>Lưu ý về startAt vs endAt:</strong> Bean Validation chỉ kiểm tra
 * từng trường độc lập (không null). Kiểm tra {@code startAt < endAt} và số đêm
 * tối thiểu được thực hiện trong {@link com.hotelpms.pricing.service.QuoteService}.
 *
 * @param roomTypeId  ID loại phòng muốn báo giá; phải dương
 * @param startAt     Ngày giờ nhận phòng (có thông tin múi giờ qua OffsetDateTime)
 * @param endAt       Ngày giờ trả phòng (có thông tin múi giờ qua OffsetDateTime)
 * @param guestCount  Số khách; tối thiểu 1, tối đa 20
 *
 * @author Nguyễn Đình Đạt
 */
public record QuoteRequest(

        /**
         * ID loại phòng trong bảng room_types.
         * Bắt buộc có. Phải là số dương (>= 1).
         */
        @NotNull(message = "roomTypeId không được để trống")
        @Positive(message = "roomTypeId phải là số dương")
        Long roomTypeId,

        /**
         * Ngày giờ nhận phòng.
         * Phải có thông tin múi giờ (OffsetDateTime), ví dụ: 2026-11-01T12:00:00+07:00.
         * Theo kiến trúc dự án: nhận phòng mặc định 12:00 (nhưng giá tính theo ngày,
         * không theo giờ cụ thể – QuoteService tính số đêm bằng LocalDate).
         */
        @NotNull(message = "startAt không được để trống")
        OffsetDateTime startAt,

        /**
         * Ngày giờ trả phòng.
         * Phải có thông tin múi giờ (OffsetDateTime), ví dụ: 2026-11-03T10:00:00+07:00.
         * Trả phòng mặc định 10:00 ngày cuối. Validation startAt < endAt và
         * số đêm >= 1 được thực hiện trong QuoteService.
         */
        @NotNull(message = "endAt không được để trống")
        OffsetDateTime endAt,

        /**
         * Số lượng khách.
         * Giới hạn từ 1 đến 20. Nếu vượt quá maxOccupancy của loại phòng,
         * QuoteService sẽ ném lỗi 400 GUEST_COUNT_EXCEEDS_CAPACITY.
         */
        @Min(value = 1, message = "guestCount phải ít nhất là 1")
        @Max(value = 20, message = "guestCount không được vượt quá 20")
        int guestCount

) {}
