package com.hotelpms.pricing.dto;

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

) {}
