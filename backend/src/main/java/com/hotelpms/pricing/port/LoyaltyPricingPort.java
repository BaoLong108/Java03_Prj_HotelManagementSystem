package com.hotelpms.pricing.port;

import com.hotelpms.loyalty.LoyaltyTier;

/**
 * Port giữa module Pricing và dữ liệu hạng khách (customer_profiles.tier_code).
 *
 * <p>Quote không tin client gửi hạng. Controller chỉ truyền userId lấy từ JWT
 * (hoặc {@code null} nếu Guest). Implementation đọc hạng thật từ DB.
 *
 * <p>Unit test mock port này; chưa cần module Loyalty hoàn chỉnh.
 *
 * @author Nguyễn Đình Đạt
 */
public interface LoyaltyPricingPort {

    /**
     * Trả về hạng loyalty hiện tại của user.
     *
     * <p>Nếu user không có {@code customer_profiles} (ví dụ STAFF/ADMIN),
     * implementation nên trả {@link LoyaltyTier#STANDARD} chứ không ném lỗi,
     * vì Guest/nhân viên vẫn được xem quote với giá gốc.
     *
     * @param userId ID từ JWT subject, không được {@code null}
     * @return hạng đang áp dụng, không {@code null}
     */
    LoyaltyTier findTierByUserId(Long userId);
}
