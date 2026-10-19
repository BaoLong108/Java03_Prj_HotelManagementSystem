package com.hotelpms.loyalty;

import com.hotelpms.loyalty.dto.MembershipBenefits;
import java.math.BigDecimal;

/** Read-only contract owned by Loyalty for Pricing and other server-side consumers. */
public interface MembershipBenefitsProvider {
    /**
     * Uses eligible spending already recorded by the server, excluding the proposed stay.
     * Returns STANDARD/0.00, VIP/0.10 or DIAMOND/0.15; does not persist an award or tier.
     * Invalid amounts fail with ApiException (INVALID_LOYALTY_AMOUNT), never a silent discount fallback.
     */
    MembershipBenefits getMembershipBenefits(BigDecimal eligibleSpendTotal);
}
