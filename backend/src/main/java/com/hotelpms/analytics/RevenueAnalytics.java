package com.hotelpms.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record RevenueAnalytics(
        LocalDate from, LocalDate to, String hotelZone, Long roomTypeId,
        Totals totals, Inventory inventory, List<Day> days, List<RoomType> roomTypes,
        Map<String, Long> membershipDistribution, Comparison comparison
) {
    public record Totals(
            long reservations, long pendingPayments, long confirmed, long completed, long cancelled, long expired,
            long cancellations, long demoSettlements, BigDecimal demoPaymentVolume, BigDecimal confirmedBookingValue,
            BigDecimal membershipDiscount, BigDecimal voucherDiscount, BigDecimal averageDemoBookingValue,
            BigDecimal cancellationRate
    ) {}

    public record Inventory(long bookedNights, long estimatedAvailableNights, BigDecimal bookedUtilization) {}

    public record Day(LocalDate date, long reservations, long cancellations, BigDecimal demoPaymentVolume) {}

    public record RoomType(long id, String name, long reservations, BigDecimal demoPaymentVolume) {}

    public record Comparison(LocalDate from, LocalDate to, BigDecimal demoPaymentVolume) {}
}
