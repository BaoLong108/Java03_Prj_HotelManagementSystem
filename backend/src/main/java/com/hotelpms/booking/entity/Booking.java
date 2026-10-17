package com.hotelpms.booking.entity;

import com.hotelpms.shared.model.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "bookings")
public class Booking extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_code", nullable = false, unique = true, length = 30)
    private String bookingCode;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "room_type_id", nullable = false)
    private Long roomTypeId;

    @Column(name = "physical_room_id", nullable = false)
    private Long physicalRoomId;

    @Column(name = "guest_count", nullable = false)
    private short guestCount;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "hold_expires_at", nullable = false)
    private Instant holdExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BookingStatus status = BookingStatus.PENDING_PAYMENT;

    @Column(name = "base_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "discount_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal discountRate = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "payable_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal payableAmount;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    protected Booking() {
    }

    public Booking(
            String bookingCode,
            Long customerId,
            Long roomTypeId,
            Long physicalRoomId,
            short guestCount,
            Instant startAt,
            Instant endAt,
            Instant holdExpiresAt,
            BigDecimal baseAmount,
            BigDecimal discountRate,
            BigDecimal discountAmount,
            BigDecimal payableAmount
    ) {
        this.bookingCode = Objects.requireNonNull(bookingCode, "bookingCode must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.roomTypeId = Objects.requireNonNull(roomTypeId, "roomTypeId must not be null");
        this.physicalRoomId = Objects.requireNonNull(physicalRoomId, "physicalRoomId must not be null");
        this.guestCount = guestCount;
        this.startAt = Objects.requireNonNull(startAt, "startAt must not be null");
        this.endAt = Objects.requireNonNull(endAt, "endAt must not be null");
        this.holdExpiresAt = Objects.requireNonNull(holdExpiresAt, "holdExpiresAt must not be null");
        this.status = BookingStatus.PENDING_PAYMENT;
        this.baseAmount = Objects.requireNonNull(baseAmount, "baseAmount must not be null");
        this.discountRate = discountRate != null ? discountRate : BigDecimal.ZERO;
        this.discountAmount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        this.payableAmount = Objects.requireNonNull(payableAmount, "payableAmount must not be null");
    }

    // State transition methods

    public void confirm(Instant now) {
        if (this.status != BookingStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Only PENDING_PAYMENT booking can be confirmed, current status: " + this.status);
        }
        this.status = BookingStatus.CONFIRMED;
        this.confirmedAt = Objects.requireNonNull(now, "confirmedAt must not be null");
    }

    public void complete(Instant now) {
        if (this.status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Only CONFIRMED booking can be completed, current status: " + this.status);
        }
        this.status = BookingStatus.COMPLETED;
        this.completedAt = Objects.requireNonNull(now, "completedAt must not be null");
    }

    public void cancel(Instant now) {
        if (this.status != BookingStatus.PENDING_PAYMENT && this.status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Booking with status " + this.status + " cannot be cancelled");
        }
        this.status = BookingStatus.CANCELLED;
        this.cancelledAt = Objects.requireNonNull(now, "cancelledAt must not be null");
    }

    public void expire(Instant now) {
        if (this.status != BookingStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Only PENDING_PAYMENT booking can be expired, current status: " + this.status);
        }
        this.status = BookingStatus.EXPIRED;
        this.expiredAt = Objects.requireNonNull(now, "expiredAt must not be null");
    }

    public boolean isHoldActive(Instant now) {
        return this.status == BookingStatus.PENDING_PAYMENT && this.holdExpiresAt.isAfter(now);
    }

    // Getters

    public Long getId() {
        return id;
    }

    public String getBookingCode() {
        return bookingCode;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getRoomTypeId() {
        return roomTypeId;
    }

    public Long getPhysicalRoomId() {
        return physicalRoomId;
    }

    public short getGuestCount() {
        return guestCount;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public BigDecimal getDiscountRate() {
        return discountRate;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getPayableAmount() {
        return payableAmount;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getExpiredAt() {
        return expiredAt;
    }
}
