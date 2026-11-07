package com.hotelpms.loyalty.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Append-only ledger. V1 enforces one award per booking and the booking/customer FK. */
@Entity
@Table(name = "loyalty_awards")
public class LoyaltyAward {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(name = "eligible_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal eligibleAmount;
    @Column(name = "points_awarded", nullable = false)
    private long pointsAwarded;
    @Column(name = "awarded_at", nullable = false)
    private Instant awardedAt;

    protected LoyaltyAward() { }

    public LoyaltyAward(Long bookingId, Long customerId, BigDecimal eligibleAmount, long pointsAwarded, Instant awardedAt) {
        this.bookingId = bookingId;
        this.customerId = customerId;
        this.eligibleAmount = eligibleAmount;
        this.pointsAwarded = pointsAwarded;
        this.awardedAt = awardedAt;
    }
}
