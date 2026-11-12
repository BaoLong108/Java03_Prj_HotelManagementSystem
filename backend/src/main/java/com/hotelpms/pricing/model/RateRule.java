package com.hotelpms.pricing.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RateRule(Long id, Kind kind, LocalDate startsOn, LocalDate endsOn, BigDecimal nightlyRate) {
    // Declaration order is precedence, highest first.
    public enum Kind { OVERRIDE, HOLIDAY, SEASONAL, WEEKEND }
}
