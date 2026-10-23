package com.hotelpms.pricing.dto;

import com.hotelpms.pricing.snapshot.PriceSnapshot;
import java.math.BigDecimal;

/**
 * DTO chứa kết quả báo giá phòng trả về cho client.
 *
 * <p>Tên các trường tiền tệ ({@code baseAmount}, {@code discountRate},
 * {@code discountAmount}, {@code payableAmount}) tuân theo quy ước đã thống nhất
 * trong {@code docs/api-contract.md}.
 *
 * <p>Đảm bảo bất biến: {@code payableAmount = baseAmount - discountAmount}.
 * Ràng buộc này cũng được database PostgreSQL kiểm tra ở bảng {@code bookings}
 * qua constraint {@code ck_bookings_amounts}.
 *
 * <p>Tất cả giá trị tiền tệ là {@link BigDecimal} với 2 chữ số thập phân.
 * Tuyệt đối không dùng {@code double} hay {@code float} để tránh lỗi làm tròn
 * tích lũy khi tính số tiền lớn (VND).
 *
 * @param roomTypeId         ID loại phòng đã báo giá
 * @param nights             Số đêm lưu trú = toLocalDate(endAt) - toLocalDate(startAt)
 * @param basePricePerNight  Giá cơ bản mỗi đêm từ bảng room_types
 * @param baseAmount         Tổng giá chưa giảm = basePricePerNight × nights
 * @param discountRate       Tỷ lệ giảm giá: 0.00 / 0.10 / 0.15 tương ứng STANDARD/VIP/DIAMOND
 * @param discountAmount     Số tiền được giảm = baseAmount × discountRate (HALF_UP, 2 decimal)
 * @param payableAmount      Số tiền phải trả = baseAmount - discountAmount
 * @param appliedTier        Hạng đã áp dụng để tính giảm giá: "STANDARD", "VIP" hoặc "DIAMOND"
 *
 * @author Nguyễn Đình Đạt
 */
public record QuoteResponse(

        Long roomTypeId,

        long nights,

        BigDecimal basePricePerNight,

        BigDecimal baseAmount,

        BigDecimal discountRate,

        BigDecimal discountAmount,

        BigDecimal payableAmount,

        String appliedTier

) {

    /**
     * Chuyển kết quả báo giá thành {@link PriceSnapshot} để lưu xuống booking.
     *
     * <p>Ánh xạ là 1-1 và giữ nguyên từng giá trị: {@code baseAmount},
     * {@code discountRate}, {@code discountAmount}, {@code payableAmount} và
     * {@code appliedTier}. {@link PriceSnapshot} tự kiểm tra lại bất biến
     * {@code payableAmount = baseAmount - discountAmount} nên giá trị trả về luôn
     * hợp lệ với cùng công thức mà {@link com.hotelpms.pricing.service.QuoteService}
     * đã dùng.
     *
     * <p>Không ảnh hưởng JSON contract của endpoint: {@link #toSnapshot()} là method
     * thuần tuý, các trường hiện có vẫn được serialize như trước.
     *
     * @return snapshot chứa đúng các giá trị tiền và tỷ lệ của lần báo giá này
     */
    public PriceSnapshot toSnapshot() {
        return new PriceSnapshot(baseAmount, discountRate, discountAmount, payableAmount, appliedTier);
    }
}
