package com.hotelpms.loyalty.dto;

import com.hotelpms.loyalty.LoyaltyTier;
import java.math.BigDecimal;

/** Server-calculated benefits for Pricing. discountRate is a fraction, e.g. 0.10. */
public record MembershipBenefits(
        LoyaltyTier tierCode,
        BigDecimal eligibleSpendTotal,
        BigDecimal discountRate) { }
