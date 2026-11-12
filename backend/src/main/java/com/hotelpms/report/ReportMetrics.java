package com.hotelpms.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Pure room-night calculation. All accounting money remains BigDecimal on the server. */
final class ReportMetrics {
    record Stay(Instant start, Instant end) { }
    record Block(long roomId, Instant start, Instant end) { }
    record Day(LocalDate date, String revenue, long soldRoomNights, long availableRoomNights, BigDecimal occupancyRate) { }
    static BigDecimal rate(long sold, long available) {
        return available == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(sold)
                .divide(BigDecimal.valueOf(available), 6, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    }
    static String revpar(BigDecimal revenue, long available) {
        return available == 0 ? "0.00" : revenue.divide(BigDecimal.valueOf(available), 2, RoundingMode.HALF_UP).toPlainString();
    }
    static List<Day> days(ReportFilters.Period period, Set<Long> activeRooms, List<Stay> stays,
            List<Block> blocks, Map<LocalDate, BigDecimal> revenues) {
        var result = new ArrayList<Day>();
        for (var day = period.from(); day.isBefore(period.to()); day = day.plusDays(1)) {
            long sold = 0;
            for (var stay : stays) {
                var arrival = stay.start().atZone(ReportFilters.ZONE).toLocalDate();
                var departure = stay.end().atZone(ReportFilters.ZONE).toLocalDate();
                if (!day.isBefore(arrival) && day.isBefore(departure)) sold++;
            }
            // A room-night is unavailable if an unreleased block intersects the standard 12:00–10:00 stay.
            Instant start = day.atTime(12, 0).atZone(ReportFilters.ZONE).toInstant();
            Instant end = day.plusDays(1).atTime(10, 0).atZone(ReportFilters.ZONE).toInstant();
            Set<Long> blocked = new HashSet<>();
            for (var block : blocks) if (activeRooms.contains(block.roomId()) && block.start().isBefore(end)
                    && block.end().isAfter(start)) blocked.add(block.roomId());
            long available = activeRooms.size() - blocked.size();
            result.add(new Day(day, revenues.getOrDefault(day, BigDecimal.ZERO).toPlainString(), sold, available, rate(sold, available)));
        }
        return List.copyOf(result);
    }
}
