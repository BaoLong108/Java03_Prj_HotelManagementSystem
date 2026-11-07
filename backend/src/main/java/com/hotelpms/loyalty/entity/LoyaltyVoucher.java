package com.hotelpms.loyalty.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "loyalty_vouchers")
public class LoyaltyVoucher {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 6)
    private String code;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(name = "points_cost", nullable = false)
    private int pointsCost;
    @Column(name = "discount_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountAmount;
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;
    @Column(name = "booking_id", unique = true)
    private Long bookingId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "used_at")
    private Instant usedAt;

    protected LoyaltyVoucher() { }

    public void reserve(Long bookingId) { this.bookingId = bookingId; }
    public void release() { if (usedAt == null) bookingId = null; }
    public void consume(Instant now) { this.usedAt = now; }
    public Long getId() { return id; }
    public String getCode() { return code; }
    public Long getCustomerId() { return customerId; }
    public int getPointsCost() { return pointsCost; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public Long getBookingId() { return bookingId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUsedAt() { return usedAt; }
    public String getStatus() { return usedAt != null ? "USED" : bookingId != null ? "RESERVED" : "AVAILABLE"; }
}
