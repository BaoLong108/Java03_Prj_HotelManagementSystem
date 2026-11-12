package com.hotelpms.pricing.service;

import com.hotelpms.pricing.model.RateRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Resolves the same effective nights for the calendar, its preview, and the existing quote. */
final class NightlyPricing {
    private NightlyPricing() { }

    record Night(LocalDate date, BigDecimal nightlyRate, boolean weekend, RateRule.Kind kind, Long ruleId) { }

    static Night resolve(LocalDate date, BigDecimal base, List<RateRule> rules) {
        boolean weekend = isWeekend(date);
        RateRule winner = rules.stream().filter(rule -> appliesOn(rule, date))
                .min(Comparator.comparing(RateRule::kind)).orElse(null);
        if (winner == null) return new Night(date, base, weekend, null, null);
        return new Night(date, winner.nightlyRate(), weekend, winner.kind(), winner.id());
    }

    static BigDecimal total(LocalDate from, LocalDate checkout, BigDecimal base, List<RateRule> rules) {
        var affectedNights = new HashMap<LocalDate, RateRule>();
        // A persisted rule spans at most 366 nights. Avoid iterating long stays at the base rate.
        for (RateRule rule : rules.stream().sorted(Comparator.comparing(RateRule::kind)).toList()) {
            LocalDate first = rule.startsOn().isAfter(from) ? rule.startsOn() : from;
            LocalDate last = rule.endsOn().isBefore(checkout) ? rule.endsOn() : checkout.minusDays(1);
            for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
                if (appliesOn(rule, date)) affectedNights.putIfAbsent(date, rule);
            }
        }
        BigDecimal total = base.multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(from, checkout)));
        for (RateRule rule : affectedNights.values()) total = total.add(rule.nightlyRate().subtract(base));
        return total;
    }

    private static boolean appliesOn(RateRule rule, LocalDate date) {
        return !date.isBefore(rule.startsOn()) && !date.isAfter(rule.endsOn())
                && (rule.kind() != RateRule.Kind.WEEKEND || isWeekend(date));
    }

    static boolean isWeekend(LocalDate date) { return date.getDayOfWeek().getValue() >= 6; }
}
