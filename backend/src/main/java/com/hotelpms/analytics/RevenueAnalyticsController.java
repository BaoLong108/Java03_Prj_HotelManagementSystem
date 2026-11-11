package com.hotelpms.analytics;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/analytics")
class RevenueAnalyticsController {
    private final RevenueAnalyticsService analytics;
    private final RevenueAnalyticsRepository reports;

    RevenueAnalyticsController(RevenueAnalyticsService analytics, RevenueAnalyticsRepository reports) {
        this.analytics = analytics;
        this.reports = reports;
    }

    @GetMapping
    RevenueAnalytics report(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long roomTypeId) {
        return analytics.report(from, to, roomTypeId);
    }

    @GetMapping("/room-types")
    List<RevenueAnalytics.RoomType> roomTypes() { return reports.roomTypes(); }

    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    ResponseEntity<String> csv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long roomTypeId) {
        var report = analytics.report(from, to, roomTypeId);
        var csv = new StringBuilder("hotel_date,room_type_id,reservations_created,cancellations_recorded,demo_payment_volume_vnd\r\n");
        var type = roomTypeId == null ? "" : roomTypeId.toString();
        for (var day : report.days()) {
            csv.append(day.date()).append(',').append(type).append(',').append(day.reservations()).append(',')
                    .append(day.cancellations()).append(',').append(day.demoPaymentVolume().toPlainString()).append("\r\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"analytics-" + from + "-" + to + ".csv\"").body(csv.toString());
    }
}
