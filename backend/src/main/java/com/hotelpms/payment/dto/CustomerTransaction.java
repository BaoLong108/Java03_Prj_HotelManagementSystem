package com.hotelpms.payment.dto;

import com.hotelpms.booking.entity.BookingStatus;
import com.hotelpms.payment.entity.PaymentProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CustomerTransaction(
        long id, String bookingCode, String roomTypeName, String coverImageUrl, String reference,
        PaymentProvider provider, Method method, BigDecimal amount, String currency, Instant initiatedAt,
        Status status, BookingStatus bookingStatus, boolean receiptAvailable, Instant settledAt
) {
    public enum Method { VNPAY, VOUCHER, ZERO_PAYABLE, DEMO }
    public enum Status { SUCCEEDED, PENDING, PROCESSING, FAILED, CANCELLED, EXPIRED, REVIEW_REQUIRED }
    public enum Sort { NEWEST, OLDEST }

    public record Detail(
            CustomerTransaction transaction, String providerTransactionId, BigDecimal baseAmount,
            BigDecimal membershipDiscount, BigDecimal voucherDiscount, BigDecimal payableAmount,
            Instant gatewayVerifiedAt, Instant settledAt
    ) {}

    public record Page(List<CustomerTransaction> items, int page, int size, long totalElements, long totalPages) {}
}
