package com.hotelpms.loyalty.dto;
import jakarta.validation.constraints.*;
public record ApplyVoucherRequest(@NotBlank @Pattern(regexp = "[A-Z0-9]{6}", message = "Mã phải gồm 6 chữ hoa hoặc chữ số") String code) { }
