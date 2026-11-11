package com.hotelpms.payment.service;

import com.hotelpms.booking.repository.BookingRepository;
import com.hotelpms.payment.dto.*;
import com.hotelpms.payment.entity.PaymentProvider;
import com.hotelpms.payment.repository.PaymentAttemptRepository;
import com.hotelpms.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adapts the member's id-based contract to the same payment path used by the customer UI. */
@Service
public class PaymentAttemptService {
    private final BookingRepository bookings;
    private final PaymentAttemptRepository attempts;
    private final DemoPaymentService demo;
    public record Result(PaymentAttemptResponse payment, boolean replay) {}

    public PaymentAttemptService(BookingRepository bookings, PaymentAttemptRepository attempts, DemoPaymentService demo) {
        this.bookings = bookings; this.attempts = attempts; this.demo = demo;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Result pay(CreatePaymentRequest request, long actor) {
        var booking = bookings.findByIdForUpdate(request.bookingId()).filter(value -> value.getCustomerId().equals(actor))
                .orElseThrow(PaymentAttemptService::notFound);
        var existing = attempts.findByBookingIdAndProviderAndIdempotencyKey(booking.getId(), PaymentProvider.DEMO, request.idempotencyKey().strip());
        if (existing.isPresent()) {
            if (existing.get().getRequestedAmount().compareTo(request.amount()) != 0) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_AMOUNT_CONFLICT", "Khóa thanh toán đã dùng với số tiền khác.");
            }
            return new Result(PaymentAttemptResponse.from(existing.get()), true);
        }
        if (booking.getPayableAmount().compareTo(request.amount()) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_AMOUNT_MISMATCH", "Số tiền không khớp giá đã chốt của booking.");
        }
        var result = demo.pay(new DemoPaymentRequest(booking.getBookingCode(), request.idempotencyKey(),
                request.simulatedOutcome() == CreatePaymentRequest.Outcome.FAILURE), actor);
        return new Result(PaymentAttemptResponse.from(attempts.findById(result.paymentAttemptId()).orElseThrow()), false);
    }

    @Transactional(readOnly = true)
    public PaymentAttemptResponse find(long id, long actor) {
        var attempt = attempts.findById(id).orElseThrow(PaymentAttemptService::notFound);
        if (bookings.findByIdAndCustomerId(attempt.getBookingId(), actor).isEmpty()) throw notFound();
        return PaymentAttemptResponse.from(attempt);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_ATTEMPT_NOT_FOUND", "Không tìm thấy lần thanh toán hoặc booking.");
    }
}
