package com.hotelpms.report;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportMetricsTest {
    Instant at(String value) { return OffsetDateTime.parse(value + "+07:00").toInstant(); }
    @Test void clipsNightsAtHotelDatesAndCountsDistinctActiveBlockedRooms() {
        var p = ReportFilters.period("2032-06-02", "2032-06-03");
        var stays = List.of(new ReportMetrics.Stay(at("2032-06-01T12:00"), at("2032-06-03T10:00")),
                new ReportMetrics.Stay(at("2032-06-01T12:00"), at("2032-06-02T10:00")),
                new ReportMetrics.Stay(at("2032-06-03T12:00"), at("2032-06-04T10:00")));
        var blocks = List.of(new ReportMetrics.Block(2, at("2032-06-02T12:00"), at("2032-06-02T18:00")),
                new ReportMetrics.Block(2, at("2032-06-02T19:00"), at("2032-06-03T10:00")),
                new ReportMetrics.Block(99, at("2032-06-02T12:00"), at("2032-06-03T10:00")),
                new ReportMetrics.Block(1, at("2032-06-01T12:00"), at("2032-06-02T12:00")));
        var day = ReportMetrics.days(p, Set.of(1L, 2L, 3L), stays, blocks,
                Map.of(p.from(), new BigDecimal("123456789012345.67"))).getFirst();
        assertThat(day.soldRoomNights()).isEqualTo(1);
        assertThat(day.availableRoomNights()).isEqualTo(2);
        assertThat(day.occupancyRate()).isEqualByComparingTo("0.5");
        assertThat(day.revenue()).isEqualTo("123456789012345.67");
        assertThat(ReportMetrics.revpar(new BigDecimal("1.01"), 2)).isEqualTo("0.51");
    }
    @Test void emptyInventoryAndPeriodHaveDefinedZerosAndRateIsBounded() {
        var day = ReportMetrics.days(ReportFilters.period("2032-06-02", "2032-06-03"), Set.of(), List.of(), List.of(), Map.of()).getFirst();
        assertThat(day.revenue()).isEqualTo("0"); assertThat(day.availableRoomNights()).isZero();
        assertThat(day.soldRoomNights()).isZero(); assertThat(day.occupancyRate()).isEqualByComparingTo("0");
        assertThat(ReportMetrics.rate(2, 1)).isEqualByComparingTo("1");
        assertThat(ReportMetrics.revpar(BigDecimal.TEN, 0)).isEqualTo("0.00");
    }
    @Test void validatesStrictHalfOpenPeriodsAndNinetyTwoDayLimit() {
        assertThat(ReportFilters.period("2032-01-01", "2032-04-02")).isNotNull();
        for (String to : List.of("2032-01-01", "2031-12-31", "2032-04-03", "2032-02-30"))
            assertThatThrownBy(() -> ReportFilters.period("2032-01-01", to)).hasMessageContaining("Bộ lọc");
    }
    @Test void csvQuotesUnicodeNewlinesAndNeutralizesSpreadsheetFormulaPrefixes() {
        assertThat(ReportCsvService.cell("Phòng, \"đẹp\"\r\nDòng 2")).isEqualTo("\"Phòng, \"\"đẹp\"\" ↵ Dòng 2\"");
        for (String dangerous : List.of("=1+1", "+cmd", "-1", "@SUM(A1)", " \t=1", "\r\n+1"))
            assertThat(ReportCsvService.cell(dangerous)).startsWith("\"'");
        assertThat(ReportCsvService.cell(null)).isEqualTo("\"\"");
    }
}
