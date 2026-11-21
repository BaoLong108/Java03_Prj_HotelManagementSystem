package com.hotelpms.report;

import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReportController {
    private final ReportService reports;
    private final ReportCsvService csv;
    public ReportController(ReportService reports, ReportCsvService csv) { this.reports = reports; this.csv = csv; }
    @GetMapping("/api/v1/staff/reports/summary")
    public ReportService.Summary summary(@RequestParam(required=false) String from, @RequestParam(required=false) String to) {
        return reports.summary(from, to);
    }
    @GetMapping("/api/v1/staff/reports/room-block-history")
    public List<ReportService.History> history(@RequestParam(required=false) String from, @RequestParam(required=false) String to,
            @RequestParam(required=false) String roomId) { return reports.history(from, to, roomId); }
    @GetMapping("/api/v1/admin/reports/bookings.csv")
    public ResponseEntity<byte[]> csv(@RequestParam(required=false) String from, @RequestParam(required=false) String to) {
        byte[] body = csv.bookings(from, to);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bookings-" + from + "-" + to + ".csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
