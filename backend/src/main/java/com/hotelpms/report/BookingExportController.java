package com.hotelpms.report;

import com.hotelpms.shared.api.ApiException;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BookingExportController {
    private final JdbcTemplate jdbc;

    BookingExportController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping(value = "/api/v1/admin/bookings/export.csv", produces = "text/csv;charset=UTF-8")
    @Transactional(readOnly = true)
    ResponseEntity<String> export(@RequestParam String from, @RequestParam String to,
            @RequestParam(required = false) String roomTypeId, @RequestParam(required = false) String status) {
        var first = ReportFilters.date(from);
        var last = ReportFilters.date(to);
        if (last.isBefore(first) || ChronoUnit.DAYS.between(first, last) >= 366) throw ReportFilters.invalid();
        var type = ReportFilters.id(roomTypeId);
        var state = ReportFilters.status(status);
        var rows = jdbc.query("""
                WITH events AS (
                    SELECT 'BOOKING' AS kind, b.booking_code AS reference, b.status, b.room_type_id,
                        b.physical_room_id, b.start_at, b.end_at, b.payable_amount::text AS payable, '' AS reason
                    FROM bookings b
                    WHERE b.start_at < ? AND b.end_at > ? AND (?::text IS NULL OR b.status=?)
                    UNION ALL
                    SELECT 'BLOCK', rb.id::text, CASE WHEN rb.released_at IS NULL THEN 'ACTIVE' ELSE 'RELEASED' END,
                        r.room_type_id, rb.physical_room_id, rb.start_at, rb.end_at, '', rb.reason
                    FROM room_blocks rb JOIN physical_rooms r ON r.id=rb.physical_room_id
                    WHERE rb.start_at < ? AND rb.end_at > ?
                )
                SELECT e.*, r.room_number, t.name AS room_type_name
                FROM events e JOIN physical_rooms r ON r.id=e.physical_room_id JOIN room_types t ON t.id=e.room_type_id
                WHERE (?::bigint IS NULL OR e.room_type_id=?)
                ORDER BY e.start_at, e.kind, e.reference LIMIT 10001
                """, (rs, row) -> String.join(",", cell(rs.getString("kind")), cell(rs.getString("reference")),
                        cell(rs.getString("status")), cell(rs.getString("room_number")), cell(rs.getString("room_type_name")),
                        cell(rs.getTimestamp("start_at").toInstant().atZone(ReportFilters.ZONE).toOffsetDateTime().toString()),
                        cell(rs.getTimestamp("end_at").toInstant().atZone(ReportFilters.ZONE).toOffsetDateTime().toString()),
                        cell(rs.getString("payable")), cell(rs.getString("reason"))),
                Timestamp.from(last.plusDays(1).atStartOfDay(ReportFilters.ZONE).toInstant()),
                Timestamp.from(first.atStartOfDay(ReportFilters.ZONE).toInstant()), state, state,
                Timestamp.from(last.plusDays(1).atStartOfDay(ReportFilters.ZONE).toInstant()),
                Timestamp.from(first.atStartOfDay(ReportFilters.ZONE).toInstant()), type, type);
        if (rows.size() > 10000) throw new ApiException(HttpStatus.BAD_REQUEST, "EXPORT_TOO_LARGE", "Chọn kỳ hoặc hạng phòng nhỏ hơn, tối đa 10.000 dòng.");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"booking-calendar-" + first + "-" + last + ".csv\"")
                .body("\uFEFFkind,reference,status,room_number,room_type,start_at_hotel,end_at_hotel,payable_vnd,block_reason\r\n"
                        + String.join("\r\n", rows) + "\r\n");
    }

    static String cell(String value) {
        String text = value == null ? "" : value;
        String start = text.stripLeading();
        if ((!start.isEmpty() && "=+-@".indexOf(start.charAt(0)) >= 0) || text.startsWith("\t") || text.startsWith("\r")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
