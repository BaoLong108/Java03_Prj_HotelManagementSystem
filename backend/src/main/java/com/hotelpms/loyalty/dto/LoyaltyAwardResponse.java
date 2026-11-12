package com.hotelpms.loyalty.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record LoyaltyAwardResponse(String bookingCode, BigDecimal eligibleAmount, long pointsAwarded, Instant awardedAt) { }
