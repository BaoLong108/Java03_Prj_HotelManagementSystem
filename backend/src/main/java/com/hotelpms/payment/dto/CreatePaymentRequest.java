package com.hotelpms.payment.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Đình Đạt's payment request contract, integrated with the existing Booking and Voucher transaction. */
public record CreatePaymentRequest(
        @NotNull @Positive Long bookingId,
        @NotBlank @Size(max = 100) String idempotencyKey,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        Outcome simulatedOutcome
) {
    public enum Outcome { SUCCESS, FAILURE }
}
