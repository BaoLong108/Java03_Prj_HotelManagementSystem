package com.hotelpms.pricing.dto;

import com.hotelpms.pricing.model.RateRule;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RateRuleRequest(@NotNull @Positive Long roomTypeId, @NotNull RateRule.Kind kind,
                              @NotNull LocalDate startsOn, @NotNull LocalDate endsOn,
                              @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal nightlyRate) {
    public record BaseRate(@NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal nightlyRate) { }
}
