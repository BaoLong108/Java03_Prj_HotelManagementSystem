package com.hotelpms.pricing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record NightlyPrice(LocalDate date, BigDecimal nightlyRate) {}
