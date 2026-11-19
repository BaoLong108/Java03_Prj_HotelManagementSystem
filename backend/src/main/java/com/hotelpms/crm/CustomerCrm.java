package com.hotelpms.crm;

import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.loyalty.LoyaltyTier;
import com.hotelpms.loyalty.dto.MembershipSummary;
import com.hotelpms.payment.dto.CustomerTransaction.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CustomerCrm {
    private CustomerCrm() {}

    public enum Sort { REGISTERED, SPENDING, POINTS }
    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) {}
    public record Statistics(long total, long standard, long vip, long diamond) {}
    public record Directory(Statistics statistics, Page<Customer> customers) {}
    public record Customer(long id, String fullName, String email, String phone, boolean active, Instant registeredAt,
            LoyaltyTier tier, long points, BigDecimal eligibleSpending, long bookingCount) {}
    public record Profile(Customer customer, MembershipSummary membership, long completedBookings, long verifiedPayments,
            long pointsEarned, long pointsRedeemed) {}
    public record Booking(String bookingCode, String roomType, Instant startAt, Instant endAt, int guests,
            Instant createdAt, BookingStatus status, Status paymentStatus, BigDecimal baseAmount,
            BigDecimal membershipDiscount, BigDecimal voucherDiscount, BigDecimal payableAmount) {}
    public record Point(long id, String type, long points, Instant at, String bookingCode, String voucherCode) {}
    public record Voucher(long id, String code, int points, BigDecimal discount, Instant createdAt,
            String status, String bookingCode, Instant usedAt) {}
    public record CheckIn(long id, String bookingCode, Instant createdAt, Instant estimatedArrival,
            boolean early, String status, String reviewer, Instant reviewedAt, String rejectionReason) {}
}
