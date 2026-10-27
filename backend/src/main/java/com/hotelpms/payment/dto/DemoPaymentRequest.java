package com.hotelpms.payment.dto;
import jakarta.validation.constraints.*;
public record DemoPaymentRequest(@NotBlank String bookingCode,@NotBlank @Size(max=100) String idempotencyKey,
        Boolean simulateFailure) {}
