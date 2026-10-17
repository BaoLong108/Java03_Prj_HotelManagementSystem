package com.hotelpms.booking.dto;

import com.hotelpms.booking.entity.Booking;
import com.hotelpms.booking.entity.BookingStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record BookingResponse(
        Long id,
        String bookingCode,
        Long customerId,
        Long roomTypeId,
        short guestCount,
        Instant startAt,
        Instant endAt,
        Instant holdExpiresAt,
        BookingStatus status,
        BigDecimal baseAmount,
        BigDecimal discountRate,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        Instant createdAt,
        Instant confirmedAt,
        Instant completedAt,
        Instant cancelledAt,
        Instant expiredAt
) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getBookingCode(),
                booking.getCustomerId(),
                booking.getRoomTypeId(),
                booking.getGuestCount(),
                booking.getStartAt(),
                booking.getEndAt(),
                booking.getHoldExpiresAt(),
                booking.getStatus(),
                booking.getBaseAmount(),
                booking.getDiscountRate(),
                booking.getDiscountAmount(),
                booking.getPayableAmount(),
                booking.getCreatedAt(),
                booking.getConfirmedAt(),
                booking.getCompletedAt(),
                booking.getCancelledAt(),
                booking.getExpiredAt()
        );
    }
}
