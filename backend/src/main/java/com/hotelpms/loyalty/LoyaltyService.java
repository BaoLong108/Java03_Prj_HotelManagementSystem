package com.hotelpms.loyalty;

import com.hotelpms.loyalty.dto.MembershipBenefits;
import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Pure M1-04 calculations. This service never awards points or updates a customer. */
@Service
public class LoyaltyService {
    private static final BigDecimal VIP_THRESHOLD = new BigDecimal("10000000.00");
    private static final BigDecimal DIAMOND_THRESHOLD = new BigDecimal("30000000.00");
    private static final BigDecimal POINT_UNIT = new BigDecimal("10000");
    // Matches customer_profiles.eligible_spend_total / eligible_amount NUMERIC(15,2).
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999.99");

    public LoyaltyTier resolveTier(BigDecimal eligibleSpendTotal) {
        BigDecimal total = money(eligibleSpendTotal, "eligibleSpendTotal");
        if (total.compareTo(DIAMOND_THRESHOLD) >= 0) return LoyaltyTier.DIAMOND;
        if (total.compareTo(VIP_THRESHOLD) >= 0) return LoyaltyTier.VIP;
        return LoyaltyTier.STANDARD;
    }

    public BigDecimal getDiscountRate(LoyaltyTier tierCode) {
        if (tierCode == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOYALTY_TIER",
                    "Hạng thành viên không được để trống");
        }
        return switch (tierCode) {
            case STANDARD -> new BigDecimal("0.00");
            case VIP -> new BigDecimal("0.10");
            case DIAMOND -> new BigDecimal("0.15");
        };
    }

    /** Input must already be the eligible amount actually paid, after discount and refunds. */
    public long calculateEarnedPoints(BigDecimal eligibleSpent) {
        return money(eligibleSpent, "eligibleSpent")
                .divide(POINT_UNIT, 0, RoundingMode.FLOOR).longValueExact();
    }

    public LoyaltyTier calculateTierAfterSpending(BigDecimal currentSpent, BigDecimal additionalSpent) {
        BigDecimal total = money(currentSpent, "currentSpent").add(money(additionalSpent, "additionalSpent"));
        return resolveTier(total); // Also rejects a sum exceeding the existing database column.
    }

    /** Caller supplies the current total loaded from CustomerProfile, never a client-provided tier. */
    public MembershipBenefits getMembershipBenefits(BigDecimal eligibleSpendTotal) {
        BigDecimal total = money(eligibleSpendTotal, "eligibleSpendTotal");
        LoyaltyTier tier = resolveTier(total);
        return new MembershipBenefits(tier, total, getDiscountRate(tier));
    }

    private BigDecimal money(BigDecimal amount, String field) {
        if (amount == null || amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw invalidAmount(field);
        }
        // Accept redundant trailing zeros, but never silently round money into another tier/point.
        // Check scale before setScale to reject huge fractional exponents without allocating powers of ten.
        BigDecimal normalized = amount.stripTrailingZeros();
        if (normalized.scale() > 2) throw invalidAmount(field);
        return normalized.setScale(2, RoundingMode.UNNECESSARY);
    }

    private ApiException invalidAmount(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOYALTY_AMOUNT",
                field + " phải từ 0 đến 9.999.999.999.999,99 VND và có tối đa 2 chữ số thập phân");
    }
}
