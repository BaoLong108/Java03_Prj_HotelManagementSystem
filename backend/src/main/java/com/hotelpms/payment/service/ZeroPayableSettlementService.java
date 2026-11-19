package com.hotelpms.payment.service;

import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.loyalty.VoucherService;
import com.hotelpms.payment.dto.PaymentResponse;
import com.hotelpms.payment.entity.*;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import com.hotelpms.shared.api.ApiException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZeroPayableSettlementService {
    private final BookingRepository bookings;
    private final PaymentAttemptRepository payments;
    private final VoucherService vouchers;
    public ZeroPayableSettlementService(BookingRepository bookings, PaymentAttemptRepository payments, VoucherService vouchers) {
        this.bookings = bookings; this.payments = payments; this.vouchers = vouchers;
    }
    @Transactional
    public PaymentResponse settle(String code, long actor) {
        var booking = bookings.findByBookingCodeForUpdate(code).orElseThrow(ZeroPayableSettlementService::notFound);
        if (!booking.getCustomerId().equals(actor)) throw notFound();
        var previous = payments.findByBookingIdAndProviderAndIdempotencyKey(booking.getId(), PaymentProvider.ZERO_PAYABLE, "zero-" + code);
        if (previous.isPresent()) return PaymentResponse.from(previous.get(), booking);
        if (booking.getPayableAmount().signum() != 0) throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_REQUIRED", "Booking còn tiền phải trả. Vui lòng thanh toán VNPay.");
        var now = Instant.now();
        if (!booking.isHoldActive(now)) throw new ApiException(HttpStatus.CONFLICT, "BOOKING_EXPIRED", "Booking không còn trong thời gian giữ chỗ.");
        if (payments.existsByBookingIdAndStatus(booking.getId(), PaymentStatus.SUCCEEDED)
                || payments.findFirstByBookingIdAndProviderAndStatusOrderByCreatedAtDescIdDesc(booking.getId(), PaymentProvider.VNPAY, PaymentStatus.PENDING).isPresent())
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_IN_PROGRESS", "Booking đã hoặc đang được thanh toán.");
        var payment = new PaymentAttempt(booking.getId(), PaymentProvider.ZERO_PAYABLE, "zero-" + code, booking.getPayableAmount());
        vouchers.consumeForPayment(booking, now); payment.markSucceeded(now); payments.saveAndFlush(payment);
        booking.confirm(now); bookings.saveAndFlush(booking);
        return PaymentResponse.from(payment, booking);
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Không tìm thấy booking."); }
}
