package com.hotelpms.analytics;

import static com.hotelpms.booking.service.BookingService.HOTEL_ZONE;

import com.hotelpms.shared.api.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class RevenueAnalyticsService {
    // Bundled PostgreSQL can ship the original tzdata name without the Ho_Chi_Minh alias.
    private static final String DATABASE_HOTEL_ZONE = "Asia/Saigon";
    private final RevenueAnalyticsRepository reports;

    RevenueAnalyticsService(RevenueAnalyticsRepository reports) { this.reports = reports; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RevenueAnalytics report(LocalDate from, LocalDate to, Long roomTypeId) {
        if (from.isBefore(LocalDate.of(1900, 1, 1)) || to.isAfter(LocalDate.of(9999, 12, 30))
                || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 366
                || (roomTypeId != null && roomTypeId < 1)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ANALYTICS_FILTER", "Chọn kỳ báo cáo hợp lệ, tối đa 366 ngày");
        }
        var filters = filters(from, to, roomTypeId);
        var rows = reports.aggregates(filters);
        var overall = rows.stream().filter(row -> row.day() == null && row.typeId() == null).findFirst().orElseThrow();
        var totals = new RevenueAnalytics.Totals(overall.reservations(), overall.pending(), overall.confirmed(),
                overall.completed(), overall.cancelled(), overall.expired(), overall.cancellations(), overall.settlements(),
                overall.volume(), overall.confirmedValue(), overall.membership(), overall.voucher(),
                divide(overall.volume(), overall.settlements()), percent(overall.cancelled(), overall.reservations()));
        var capacity = reports.inventory(filters);
        var inventory = new RevenueAnalytics.Inventory(capacity.booked(), capacity.available(),
                percent(capacity.booked(), capacity.available()));
        var dailyRows = rows.stream().filter(row -> row.day() != null)
                .collect(Collectors.toMap(RevenueAnalyticsRepository.Aggregate::day, Function.identity()));
        var days = from.datesUntil(to.plusDays(1)).map(day -> {
            var row = dailyRows.get(day);
            return row == null ? new RevenueAnalytics.Day(day, 0, 0, BigDecimal.ZERO)
                    : new RevenueAnalytics.Day(day, row.reservations(), row.cancellations(), row.volume());
        }).toList();
        var types = rows.stream().filter(row -> row.typeId() != null)
                .map(row -> new RevenueAnalytics.RoomType(row.typeId(), row.typeName(), row.reservations(), row.volume())).toList();
        var tiers = new LinkedHashMap<String, Long>();
        for (String tier : new String[] {"STANDARD", "VIP", "DIAMOND"}) tiers.put(tier, 0L);
        reports.membership(filters).forEach(tier -> tiers.put(tier.tier(), tier.count()));
        return new RevenueAnalytics(from, to, HOTEL_ZONE.getId(), roomTypeId, totals, inventory, days, types, tiers,
                compare(from, to, roomTypeId, totals.demoSettlements()));
    }

    private RevenueAnalytics.Comparison compare(LocalDate from, LocalDate to, Long roomTypeId, long settlements) {
        if (settlements == 0 || !to.isBefore(LocalDate.now(HOTEL_ZONE))) return null;
        LocalDate previousFrom = from.minusDays(ChronoUnit.DAYS.between(from, to) + 1);
        LocalDate previousTo = from.minusDays(1);
        var previous = reports.demoVolume(filters(previousFrom, previousTo, roomTypeId));
        return previous.settlements() == 0 ? null : new RevenueAnalytics.Comparison(previousFrom, previousTo, previous.amount());
    }

    private static MapSqlParameterSource filters(LocalDate from, LocalDate to, Long typeId) {
        return new MapSqlParameterSource().addValue("from", from).addValue("to", to)
                .addValue("begin", from.atStartOfDay(HOTEL_ZONE).toOffsetDateTime())
                .addValue("end", to.plusDays(1).atStartOfDay(HOTEL_ZONE).toOffsetDateTime())
                .addValue("zone", DATABASE_HOTEL_ZONE).addValue("roomTypeId", typeId, java.sql.Types.BIGINT);
    }

    private static BigDecimal percent(long numerator, long denominator) {
        return divide(BigDecimal.valueOf(numerator).movePointRight(2), denominator);
    }

    private static BigDecimal divide(BigDecimal numerator, long denominator) {
        return denominator == 0 ? null : numerator.divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
}
