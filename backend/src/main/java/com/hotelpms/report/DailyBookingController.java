package com.hotelpms.report;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DailyBookingController {
    private final DailyBookingService reports;
    public DailyBookingController(DailyBookingService reports) { this.reports = reports; }
    @GetMapping("/api/v1/staff/bookings/daily")
    public DailyBookingService.DailyResult daily(@RequestParam(required = false) String date,
            @RequestParam(required = false) String roomTypeId, @RequestParam(required = false) String status) {
        return reports.daily(date, roomTypeId, status);
    }
}
