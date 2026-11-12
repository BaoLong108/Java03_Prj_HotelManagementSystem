package com.hotelpms.loyalty.dto;

import com.hotelpms.loyalty.LoyaltyTier;
import java.math.BigDecimal;

public record MembershipSummary(LoyaltyTier tierCode, BigDecimal eligibleSpendTotal, long pointsBalance,
        BigDecimal discountRate, LoyaltyTier nextTier, BigDecimal nextTierSpend, BigDecimal remainingSpend) { }
