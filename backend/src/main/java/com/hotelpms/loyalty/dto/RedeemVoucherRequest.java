package com.hotelpms.loyalty.dto;
import jakarta.validation.constraints.*;
public record RedeemVoucherRequest(@NotNull Integer pointsCost,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,100}") String idempotencyKey) { }
