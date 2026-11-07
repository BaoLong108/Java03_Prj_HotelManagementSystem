package com.hotelpms.loyalty.dto;
import com.hotelpms.loyalty.entity.LoyaltyVoucher;
import java.math.BigDecimal;
import java.time.Instant;
public record VoucherResponse(String code, int pointsCost, BigDecimal discountAmount, String status,
        Long bookingId, Instant createdAt, Instant usedAt) {
    public static VoucherResponse from(LoyaltyVoucher voucher) {
        return new VoucherResponse(voucher.getCode(), voucher.getPointsCost(), voucher.getDiscountAmount(),
                voucher.getStatus(), voucher.getBookingId(), voucher.getCreatedAt(), voucher.getUsedAt());
    }
}
